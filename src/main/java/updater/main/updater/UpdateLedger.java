package updater.main.updater;

import updater.util.ItemProgress;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次更新任务的「台账」：每一只股票 / 每一季财报占一行，状态和原因都记在这。
 *
 * <p><b>它解决的就是「中断以后不知道哪些下了、哪些有问题」</b>：</p>
 * <ul>
 *   <li>开跑前把要做的清单一次性铺出来（全部「未开始」），跑到哪一行改哪一行；</li>
 *   <li>状态变化<b>不直接写盘</b>——先塞进队列，由后台线程每 0.8 秒整批落一次，
 *       worker 线程永远不做 IO（历史教训：日志 IO 挡在任务链上会把更新拖死）；</li>
 *   <li>进程被关、断电、kill -9，最多丢最后 0.8 秒的记录；下次启动能读出
 *       「上次跑到哪、还剩哪些没做」，直接接着跑；</li>
 *   <li>台账存在程序目录 {@code updater-runs/} 下，一次任务一个文件夹
 *       （{@code meta.properties} + {@code items.tsv}），纯文本，出问题能直接发给别人看。</li>
 * </ul>
 *
 * <p>线程安全：{@link #begin}/{@link #done}/{@link #fail} 由多个 worker 并发调用，
 * 只做「改两个 volatile 字段 + 计数 + 入队」，没有文件 IO、没有界面调用。</p>
 */
public final class UpdateLedger implements ItemProgress {

    /** 一行的状态。磁盘上用单字母代码，不受混淆影响。 */
    public enum Status {
        PENDING('P', "未开始"),
        RUNNING('R', "进行中"),
        DONE('D', "完成"),
        SKIPPED('S', "跳过"),
        FAILED('F', "失败");

        public final char code;
        public final String label;

        Status(char code, String label) {
            this.code = code;
            this.label = label;
        }

        static Status of(char code) {
            for (Status status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
            return PENDING;
        }
    }

    /** 台账里的一行 */
    public static final class Item {
        /** 股票代码 / 报告期 / 任务名 */
        public final String key;
        public volatile Status status = Status.PENDING;
        /** 结果说明（「补 5 根」「新建库」）或跳过原因 */
        public volatile String detail = "";
        /** 失败原因（detail 为空时界面显示它） */
        public volatile String error = "";
        public volatile long millis;
        volatile long beginMillis;

        Item(String key) {
            this.key = key;
        }

        /** 界面上那一列要说的话 */
        public String text() {
            if (status == Status.FAILED) {
                return error.isEmpty() ? "失败（没有拿到原因）" : error;
            }
            return detail;
        }
    }

    /** 一次任务在磁盘上的汇总（历史列表 / 续跑判断都读它） */
    public static final class RunInfo {
        public File dir;
        public String name;
        public UpdateTask task;
        public long startedAt;
        public boolean finished;
        /** 手动停的（不是正常跑完，也不是被强杀）：也提供「接着跑」 */
        public boolean stopped;
        public int total;
        public int done;
        public int skipped;
        public int failed;
        public String note = "";
        public Map<String, String> params = new LinkedHashMap<String, String>();

        public int remaining() {
            return Math.max(0, total - done - skipped);
        }

        public String timeText() {
            return new SimpleDateFormat("MM-dd HH:mm").format(new Date(startedAt));
        }

        public String countText() {
            StringBuilder sb = new StringBuilder();
            sb.append(done).append('/').append(total);
            if (failed > 0) {
                sb.append(" · 失败 ").append(failed);
            }
            if (skipped > 0) {
                sb.append(" · 跳过 ").append(skipped);
            }
            return sb.toString();
        }
    }

    /** 可以接着跑的旧任务：清单 + 每一行当时的状态 */
    public static final class Resume {
        public RunInfo info;
        /** 全部清单（保持原顺序） */
        public final List<String> keys = new ArrayList<String>();
        public final Map<String, Status> statuses = new LinkedHashMap<String, Status>();
        /** 每一行当时的说明 / 失败原因（台账第 4 列，历史台账也看得到） */
        public final Map<String, String> details = new LinkedHashMap<String, String>();

        /** 需要重做的：当时没完成、没跳过、也没成功的 */
        public List<String> remainingKeys() {
            List<String> out = new ArrayList<String>();
            for (String key : keys) {
                Status status = statuses.get(key);
                if (status != Status.DONE && status != Status.SKIPPED) {
                    out.add(key);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 实例状态

    private static final String META = "meta.properties";
    private static final String ITEMS = "items.tsv";

    private final UpdateTask task;
    private final Map<String, String> params;
    private final File dir;
    private final long startedAt = System.currentTimeMillis();

    private final Object lock = new Object();
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<String, Item>();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();

    /** 待落盘的行（worker 只往这里塞，真正的 Write 在后台线程） */
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<String>();
    private final Thread flusher;
    private BufferedWriter writer;
    private volatile boolean closed;
    private volatile boolean finished;
    /** true = 用户手动停的（不是正常结束），下次启动仍然提示「接着跑」 */
    private volatile boolean stopped;
    /** 上次写 meta 的时间（3 秒节流，别每次落盘都重写一遍） */
    private volatile long lastMetaWrite;
    private volatile String note = "";

    private UpdateLedger(UpdateTask task, Map<String, String> params, File dir) throws IOException {
        this.task = task;
        this.params = params == null ? new LinkedHashMap<String, String>() : params;
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("台账目录建不出来: " + dir.getAbsolutePath());
        }
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(dir, ITEMS), true), StandardCharsets.UTF_8));
        writeMeta();
        flusher = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!closed) {
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    drain();
                }
            }
        }, "ledger-flush");
        flusher.setDaemon(true);
        flusher.start();
    }

    // ------------------------------------------------------------------ 创建 / 打开

    /** 新建一次任务的台账（目录名带时间戳，一次任务一个文件夹） */
    public static UpdateLedger create(UpdateTask task, Map<String, String> params) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File dir = new File(runsRoot(), stamp + "-" + task.id);
        // 同一秒里连点两次开始（或者扫描完立刻点修复）时间戳会撞：加序号，
        // 否则两次任务的台账会写进同一个文件夹，行互相覆盖
        int seq = 1;
        while (dir.exists()) {
            seq++;
            dir = new File(runsRoot(), stamp + "-" + task.id + "-" + seq);
        }
        return new UpdateLedger(task, params, dir);
    }

    /**
     * 用旧台账的结论续跑：新开一个台账，把上次「已完成 / 已跳过」的行照抄过来，
     * 其余重跑。这样用户看到的仍是一张完整清单，而不是「上次一半、这次一半」。
     */
    public static UpdateLedger reopen(Resume resume) throws IOException {
        UpdateLedger ledger = create(resume.info.task, resume.info.params);
        ledger.note = "续跑（" + resume.info.timeText() + " 那次没跑完的）";
        ledger.plan(resume.keys);
        for (String key : resume.keys) {
            Status status = resume.statuses.get(key);
            if (status == Status.DONE) {
                ledger.seed(key, Status.DONE, "上次已完成");
            } else if (status == Status.SKIPPED) {
                ledger.seed(key, Status.SKIPPED, "上次已跳过（本地数据来源没有数据 / 本地无库）");
            }
        }
        return ledger;
    }

    private void seed(String key, Status status, String detail) {
        Item item;
        synchronized (lock) {
            item = items.get(key);
        }
        if (item == null) {
            return;
        }
        item.status = status;
        item.detail = detail;
        if (status == Status.DONE) {
            done.incrementAndGet();
        } else if (status == Status.SKIPPED) {
            skipped.incrementAndGet();
        }
        outbox.add(statusLine(key, status, detail));
    }

    // ------------------------------------------------------------------ 台账目录

    /** 台账根目录：程序目录下的 updater-runs（程序目录不可写时退到用户目录） */
    public static File runsRoot() {
        File dir = new File(UpdaterMain.currentHome(), "updater-runs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(new File(System.getProperty("user.home", "."), ".data-tools"), "updater-runs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return new File(UpdaterMain.currentHome(), "updater-runs");
            }
        }
        return dir;
    }

    /**
     * 最近的任务记录（新的在前）。
     *
     * @param limit 最多返回几条，<=0 表示不限
     */
    public static List<RunInfo> recentRuns(int limit) {
        List<File> dirs = runDirs();
        List<RunInfo> out = new ArrayList<RunInfo>();
        for (File dir : dirs) {
            RunInfo info = readInfo(dir);
            if (info == null) {
                continue;
            }
            out.add(info);
            if (limit > 0 && out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 上一次没跑完、并且还能接着跑的任务（最新的一个） */
    public static Resume findInterrupted() {
        for (File dir : runDirs()) {
            RunInfo info = readInfo(dir);
            if (info == null || info.task == null || !info.task.resumable) {
                continue;
            }
            // 正常跑完的不用接着跑；手动停下来的算「没做完」，仍要给续跑入口
            if (info.finished && !info.stopped) {
                continue;
            }
            Resume resume = loadResume(dir, info);
            if (resume != null && !resume.remainingKeys().isEmpty()) {
                return resume;
            }
        }
        return null;
    }

    /** 读某一次任务的完整清单（历史台账里点开看明细用） */
    public static Resume loadRun(File dir) {
        RunInfo info = readInfo(dir);
        if (info == null) {
            return null;
        }
        return loadResume(dir, info);
    }

    private static List<File> runDirs() {
        File[] children = runsRoot().listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<File> dirs = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(child);
            }
        }
        // 目录名以 yyyyMMdd-HHmmss 开头，倒序就是「新的在前」
        Collections.sort(dirs, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return dirs;
    }

    private static RunInfo readInfo(File dir) {
        File meta = new File(dir, META);
        if (!meta.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(meta);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
        RunInfo info = new RunInfo();
        info.dir = dir;
        info.name = dir.getName();
        info.task = UpdateTask.byId(properties.getProperty("task"));
        info.startedAt = parseLong(properties.getProperty("started"), dir.lastModified());
        info.finished = "true".equalsIgnoreCase(properties.getProperty("finished"));
        info.stopped = "true".equalsIgnoreCase(properties.getProperty("stopped"));
        info.total = parseInt(properties.getProperty("total"));
        info.done = parseInt(properties.getProperty("done"));
        info.skipped = parseInt(properties.getProperty("skipped"));
        info.failed = parseInt(properties.getProperty("failed"));
        info.note = properties.getProperty("note", "");
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("param.")) {
                info.params.put(name.substring(6), properties.getProperty(name));
            }
        }
        return info;
    }

    private static Resume loadResume(File dir, RunInfo info) {
        File file = new File(dir, ITEMS);
        if (!file.isFile()) {
            return null;
        }
        Resume resume = new Resume();
        resume.info = info;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // 规划行：P \t 代码 ；状态行：S \t 代码 \t 状态码 \t 说明
                if (line.startsWith("P\t")) {
                    String key = line.substring(2);
                    if (!key.isEmpty() && !resume.statuses.containsKey(key)) {
                        resume.keys.add(key);
                        resume.statuses.put(key, Status.PENDING);
                    }
                } else if (line.startsWith("S\t")) {
                    String[] parts = line.split("\t", 4);
                    if (parts.length < 3) {
                        continue;
                    }
                    String key = parts[1];
                    if (!resume.statuses.containsKey(key)) {
                        resume.keys.add(key);
                    }
                    resume.statuses.put(key, Status.of(parts[2].isEmpty() ? 'P' : parts[2].charAt(0)));
                    if (parts.length > 3 && !parts[3].isEmpty()) {
                        resume.details.put(key, parts[3]);
                    }
                }
            }
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(reader);
        }
        return resume;
    }

    // ------------------------------------------------------------------ ItemProgress

    @Override
    public void planned(List<String> keys) {
        plan(keys);
    }

    /** 铺清单：全部先记「未开始」。任务开跑前调一次。 */
    public void plan(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // 按代码排序：目录里文件的顺序是乱的，台账跟着乱排会让人找不到那只
        List<String> ordered = new ArrayList<String>(keys);
        Collections.sort(ordered);
        synchronized (lock) {
            for (String key : ordered) {
                if (key == null || items.containsKey(key)) {
                    continue;
                }
                items.put(key, new Item(key));
                outbox.add("P\t" + key);
            }
        }
    }

    @Override
    public void begin(String key) {
        Item item = touch(key);
        item.status = Status.RUNNING;
        item.beginMillis = System.currentTimeMillis();
        item.detail = "";
        item.error = "";
        running.incrementAndGet();
        outbox.add(statusLine(key, Status.RUNNING, ""));
    }

    @Override
    public void done(String key, String detail) {
        finishItem(key, Status.DONE, detail == null ? "" : detail, "");
    }

    @Override
    public void skip(String key, String detail) {
        finishItem(key, Status.SKIPPED, detail == null ? "" : detail, "");
    }

    @Override
    public void fail(String key, String reason) {
        finishItem(key, Status.FAILED, "", reason == null || reason.trim().isEmpty() ? "未知错误" : reason);
    }

    private void finishItem(String key, Status status, String detail, String error) {
        Item item = touch(key);
        if (item.status == Status.RUNNING) {
            running.decrementAndGet();
        }
        item.status = status;
        item.detail = detail;
        item.error = error;
        if (item.beginMillis > 0) {
            item.millis = System.currentTimeMillis() - item.beginMillis;
        }
        if (status == Status.DONE) {
            done.incrementAndGet();
        } else if (status == Status.SKIPPED) {
            skipped.incrementAndGet();
        } else if (status == Status.FAILED) {
            failed.incrementAndGet();
        }
        outbox.add(statusLine(key, status, error.isEmpty() ? detail : error));
    }

    /** 清单外的项（有些任务事先不知道有几项，跑到才出现） */
    private Item touch(String key) {
        String safe = key == null ? "" : key;
        synchronized (lock) {
            Item item = items.get(safe);
            if (item == null) {
                item = new Item(safe);
                items.put(safe, item);
            }
            return item;
        }
    }

    // ------------------------------------------------------------------ 读给界面

    /** 当前全部行（浅拷贝，元素是活的 Item，界面只读） */
    public List<Item> items() {
        synchronized (lock) {
            return new ArrayList<Item>(items.values());
        }
    }

    public int total() {
        synchronized (lock) {
            return items.size();
        }
    }

    public int doneCount() {
        return done.get();
    }

    public int skippedCount() {
        return skipped.get();
    }

    public int failedCount() {
        return failed.get();
    }

    public int runningCount() {
        return running.get();
    }

    public int pendingCount() {
        return Math.max(0, total() - done.get() - skipped.get() - failed.get() - running.get());
    }

    /** 正在处理的几项（给状态行显示「当前：600000、600004 …」） */
    public List<String> runningKeys(int limit) {
        List<String> out = new ArrayList<String>();
        synchronized (lock) {
            for (Item item : items.values()) {
                if (item.status == Status.RUNNING) {
                    out.add(item.key);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** 失败的那些（重试 / 导出用） */
    public List<Item> failedItems() {
        List<Item> out = new ArrayList<Item>();
        for (Item item : items()) {
            if (item.status == Status.FAILED) {
                out.add(item);
            }
        }
        return out;
    }

    public UpdateTask task() {
        return task;
    }

    public File dir() {
        return dir;
    }

    public boolean isFinished() {
        return finished;
    }

    public String note() {
        return note;
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 任务正常结束：写汇总、把队列里剩下的都落盘。
     *
     * @param finishNote 括号里那句补充说明（如「已停止」「备份失败」），可为空
     */
    public void finish(String finishNote) {
        finishInternal(finishNote, false);
    }

    /**
     * 用户手动停止：终态也是「结束」，但标记成 stopped —— 下次打开仍然会给出
     * 「接着跑剩下的」入口，别让用户为了跑完 10% 再把 5000 只刷一遍。
     */
    public void finishStopped(String finishNote) {
        finishInternal(finishNote, true);
    }

    private void finishInternal(String finishNote, boolean byUserStop) {
        if (finishNote != null && !finishNote.trim().isEmpty()) {
            note = note.isEmpty() ? finishNote : note + "；" + finishNote;
        }
        stopped = byUserStop;
        finished = true;
        flushNow();
        writeMeta();
        stopFlusher();
        cleanupOld();
    }

    /** 进程要退出了：只保证已有记录落盘，不标完成（下次启动才能识别成「中断」） */
    public void flushNow() {
        drain();
        synchronized (this) {
            try {
                if (writer != null) {
                    writer.flush();
                }
            } catch (IOException ignored) {
                // 落不了盘就算了，不能让退出流程卡住
            }
        }
    }

    private void stopFlusher() {
        closed = true;
        flusher.interrupt();
        synchronized (this) {
            closeQuietly(writer);
            writer = null;
        }
    }

    private void drain() {
        if (outbox.isEmpty()) {
            return;
        }
        StringBuilder batch = new StringBuilder(4096);
        String line;
        int guard = 0;
        while ((line = outbox.poll()) != null) {
            batch.append(line).append('\n');
            if (++guard >= 4000) {
                break;
            }
        }
        if (batch.length() == 0) {
            return;
        }
        synchronized (this) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(batch.toString());
                writer.flush();
            } catch (IOException ignored) {
                // 台账写不进去不该影响更新本身
            }
        }
        // 元信息（完成/失败计数）每 3 秒跟着刷一次：进程被强杀时，历史列表里的
        // 「221/240」也是准的，不会永远停在开跑那一刻的 0/0
        if (System.currentTimeMillis() - lastMetaWrite > 3000L) {
            writeMeta();
        }
    }

    private String statusLine(String key, Status status, String text) {
        return "S\t" + (key == null ? "" : key) + "\t" + status.code + "\t" + sanitize(text);
    }

    /** 台账是「一行一条」，说明里的换行和制表符会破坏格式，统一压成空格 */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String out = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return out.length() > 300 ? out.substring(0, 300) : out;
    }

    private void writeMeta() {
        Properties properties = new Properties();
        properties.setProperty("task", task.id);
        properties.setProperty("title", task.title);
        properties.setProperty("started", Long.toString(startedAt));
        properties.setProperty("finished", Boolean.toString(finished));
        if (stopped) {
            properties.setProperty("stopped", "true");
        }
        properties.setProperty("total", Integer.toString(total()));
        properties.setProperty("done", Integer.toString(done.get()));
        properties.setProperty("skipped", Integer.toString(skipped.get()));
        properties.setProperty("failed", Integer.toString(failed.get()));
        if (!note.isEmpty()) {
            properties.setProperty("note", note);
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                properties.setProperty("param." + entry.getKey(), entry.getValue());
            }
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(dir, META));
            properties.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "StockDataUpdater");
        } catch (IOException ignored) {
            // 台账元信息写不出去不影响任务
        } finally {
            closeQuietly(out);
        }
        lastMetaWrite = System.currentTimeMillis();
    }

    /** 只留最近 40 次任务记录，免得目录越攒越大 */
    private static void cleanupOld() {
        List<File> dirs = runDirs();
        for (int i = 40; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!child.delete()) {
                        child.deleteOnExit();
                    }
                }
            }
            dir.delete();
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static int parseInt(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 关不掉就算了
            }
        }
    }
}
