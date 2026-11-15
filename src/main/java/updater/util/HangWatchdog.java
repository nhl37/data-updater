package updater.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 「卡住看门狗」：长任务跑起来以后，盯着每个执行者的阶段，一超时就<b>把当时的
 * 全部线程堆栈写到 logs/hang-*.log</b>，并在界面上说清楚卡在哪一步、已经卡了多久。
 *
 * <p><b>为什么需要它</b>：盘后下载卡在 sh600xxx.db 那种现象，光看日志只有「没有再打了」，
 * 分不清是读文件卡、建库卡、写库卡，还是日志本身写不出去。堆栈一出来就能指到具体那一行
 * （SQLite 的 native 调用、FileInputStream.read、PrintStream.write……）。</p>
 *
 * <p><b>并行之后改了监视方式</b>：现在同时有 N 个 worker 在跑，每个 worker 有自己的
 * {@link StageSink}。看门狗每 0.5 秒扫一遍所有 sink，谁停了超过阈值就单独告警、
 * 单独抓栈 —— 其余 worker 照常往下跑，不会因为一只慢就把整轮拖住。</p>
 *
 * <p><b>为什么写文件要绕开 Log</b>：{@code Log} 是全局单例 + 有全局锁，万一它自己被
 * 磁盘/杀毒/同步盘卡住，走 Log 的告警也会一起卡住 —— 那就等于没有告警。
 * 这里用独立的 {@code logs/watchdog.log}，整轮只开一次文件、2 秒批量 flush，
 * 任何 IO 异常都吞掉，绝不让诊断代码反过来拖住业务线程。</p>
 *
 * <p><b>时间格式化一律用 {@link DateTimeFormatter}</b>：不可变、线程安全。
 * 原来用的静态 {@code SimpleDateFormat} 被两把不同的锁分别保护，
 * 并发下会产出乱码时间戳甚至抛异常。</p>
 */
public final class HangWatchdog {

    /** 默认「多久没有阶段变化」就认为可疑（秒） */
    public static final int DEFAULT_SOFT_WARN_SECONDS = 20;

    /** 告警后每隔多久再提醒 / 再抓一次堆栈 */
    private static final int REPEAT_SECONDS = 30;
    /** 一轮任务里每个执行者最多提醒几次，避免日志被刷爆 */
    private static final int MAX_WARNINGS = 6;
    /** watchdog.log 超过这个大小就清空重建 */
    private static final long LOG_MAX_BYTES = 4L * 1024 * 1024;
    /** 诊断文件批量 flush 间隔：太频繁会把每一行都变成系统调用 */
    private static final long FLUSH_INTERVAL_MILLIS = 2000L;
    /** 看门狗扫描间隔 */
    private static final long SCAN_INTERVAL_MILLIS = 500L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Object FILE_LOCK = new Object();
    /** 每个正在干活的执行者一个，业务线程只写自己的那个 */
    private static final List<StageSink> SINKS = new CopyOnWriteArrayList<StageSink>();
    /** 同名 hang 文件在同一毫秒内不会互相覆盖 */
    private static final AtomicInteger DUMP_SEQ = new AtomicInteger();

    private static volatile String taskName = "";
    /** 界面日志出口，可为 null（只写文件） */
    private static volatile Consumer<String> out;
    private static volatile int softWarnSeconds = DEFAULT_SOFT_WARN_SECONDS;
    private static volatile Thread watchThread;
    private static volatile boolean running;

    private static Writer writer;
    private static long writtenBytes;
    private static long lastFlushMillis;

    private HangWatchdog() {
    }

    /** 开始看门（默认 20 秒） */
    public static void start(String task, Consumer<String> out) {
        start(task, out, DEFAULT_SOFT_WARN_SECONDS);
    }

    /**
     * 开始看门。
     *
     * <p><b>幂等</b>：同一个任务名重复调用只更新界面出口、不重启 ——
     * 盘后下载这条路原来会被调用两次，第二次会把前面注册的 sink 全清掉。</p>
     *
     * @param task        任务名（写进堆栈快照，事后知道是哪一次）
     * @param out         界面日志出口，可为 null（只写文件）
     * @param softSeconds 多少秒没有阶段变化就告警
     */
    public static void start(final String task, Consumer<String> out, int softSeconds) {
        String name = task == null ? "" : task;
        if (running && name.equals(taskName)) {
            HangWatchdog.out = out;
            softWarnSeconds = Math.max(5, softSeconds);
            return;
        }
        if (!enabled()) {
            // 关掉看门狗：只保留 logLine 写诊断文件的能力，不起监视线程
            HangWatchdog.out = out;
            return;
        }
        stop();
        taskName = name;
        HangWatchdog.out = out;
        softWarnSeconds = Math.max(5, softSeconds);
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hang-watchdog");
        thread.setDaemon(true);
        watchThread = thread;
        thread.start();
        logLine("=== 看门狗启动: " + taskName + "（超过 " + softWarnSeconds + " 秒没有进展就抓堆栈）");
    }

    /**
     * 注册一个执行者（worker 线程）的状态槽。
     *
     * <p>必须在 {@link #start} 之后调；任务结束（{@link #stop}）会自动清空。</p>
     */
    public static StageSink newSink(String name) {
        StageSink sink = new StageSink(name);
        SINKS.add(sink);
        return sink;
    }

    /** 注销一个执行者（worker 正常退出时调，避免看门狗继续盯一个已经没了的线程） */
    public static void removeSink(StageSink sink) {
        if (sink != null) {
            SINKS.remove(sink);
        }
    }

    /** 结束看门；任务正常/异常退出都要调 */
    public static void stop() {
        running = false;
        Thread thread = watchThread;
        watchThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        out = null;
        SINKS.clear();
        synchronized (FILE_LOCK) {
            flushWriter();
            closeWriter();
        }
    }

    /** 当前阶段停了多久（毫秒）——单个执行者场景用；并行时请看 {@link StageSink#idleMillis()} */
    public static long stageIdleMillis() {
        long max = 0L;
        for (StageSink sink : SINKS) {
            long idle = sink.idleMillis();
            if (idle > max) {
                max = idle;
            }
        }
        return max;
    }

    /**
     * 抓一次全线程堆栈写文件，返回文件路径。
     *
     * <p>公开出来是为了让业务代码在「单只超时」时主动抓一次，不必等看门狗的下一个周期。</p>
     */
    public static String dumpThreads(String reason) {
        File dir = new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            logLine("!! 建不出 logs 目录，堆栈没法落盘: " + reason);
            return "";
        }
        String stamp = STAMP.format(LocalDateTime.now());
        int seq = DUMP_SEQ.incrementAndGet() % 1000;
        File target = new File(dir, "hang-" + stamp + "-" + seq + ".log");

        StringBuilder sb = new StringBuilder(16384);
        sb.append("==== 卡住快照 ====\n");
        sb.append("时间: ").append(HUMAN.format(LocalDateTime.now())).append('\n');
        sb.append("任务: ").append(taskName).append('\n');
        sb.append("原因: ").append(reason).append('\n');
        sb.append("\n---- 各执行者当前阶段 ----\n");
        for (StageSink sink : SINKS) {
            sb.append("  ").append(sink.name()).append(": ").append(sink.describe())
                    .append("，这一步已停留 ").append(sink.idleMillis() / 1000).append(" 秒，")
                    .append("这一只已用 ").append(sink.elapsedMillis() / 1000).append(" 秒")
                    .append(sink.abandoned() ? "，已放弃" : "").append('\n');
        }
        sb.append("\n---- 线程堆栈 ----\n");
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == watchThread || "hang-watchdog".equals(thread.getName())) {
                continue;
            }
            sb.append("\n\"").append(thread.getName()).append('"')
                    .append(thread.isDaemon() ? " daemon" : "")
                    .append(" prio=").append(thread.getPriority())
                    .append(' ').append(thread.getState()).append('\n');
            StackTraceElement[] elements = entry.getValue();
            for (StackTraceElement element : elements) {
                sb.append("\tat ").append(element).append('\n');
            }
        }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(target),
                StandardCharsets.UTF_8)) {
            w.write(sb.toString());
            w.flush();
        } catch (IOException e) {
            logLine("!! 堆栈写文件失败(" + target.getAbsolutePath() + "): " + e.getMessage());
            return "";
        }
        logLine("已导出堆栈: " + target.getAbsolutePath() + "  原因: " + reason);
        return target.getAbsolutePath();
    }

    /**
     * 写一行诊断日志。
     *
     * <p>整轮只开一次文件，攒够 2 秒或告警时才 flush —— 原来每行都 open/write/flush/close，
     * Windows 上每次开关都会被实时防护扫一遍。</p>
     */
    public static void logLine(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        synchronized (FILE_LOCK) {
            Writer w = ensureWriter();
            if (w == null) {
                return;
            }
            try {
                w.write("[" + LocalTime.now().format(TIME) + "] " + line + System.lineSeparator());
                writtenBytes += line.length() + 16L;
                long now = System.currentTimeMillis();
                if (now - lastFlushMillis >= FLUSH_INTERVAL_MILLIS) {
                    w.flush();
                    lastFlushMillis = now;
                }
            } catch (IOException e) {
                // 诊断信息写不出去也不能影响业务
                closeWriter();
            }
        }
    }

    /** 告警：落诊断文件（立即 flush）+ 推给界面 */
    public static void warn(String message) {
        logLine("[WARN] " + message);
        synchronized (FILE_LOCK) {
            flushWriter();
        }
        Consumer<String> target = out;
        if (target != null) {
            try {
                target.accept(message);
            } catch (Throwable ignored) {
                // 界面已经不在了（窗口关掉）就当没有
            }
        }
    }

    // ================================================================ 内部

    /** 看门狗总开关（config.properties 的 updater.hang.enabled，默认开） */
    private static boolean enabled() {
        try {
            return updater.cfg.AppConfigManager.getInstance().getConfigReader()
                    .getBoolean("updater.hang.enabled", true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(SCAN_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            long threshold = softWarnSeconds * 1000L;
            for (StageSink sink : SINKS) {
                if (sink.abandoned() || sink.stageId() == StageSink.IDLE) {
                    continue;
                }
                long idle = sink.idleMillis();
                if (idle < threshold || sink.warned >= MAX_WARNINGS) {
                    continue;
                }
                // 第一次超时立刻报；之后至少隔 REPEAT_SECONDS 再报，不刷屏
                if (sink.warned > 0 && now - sink.lastWarnMillis < REPEAT_SECONDS * 1000L) {
                    continue;
                }
                sink.warned++;
                sink.lastWarnMillis = now;
                String dump = dumpThreads("[" + sink.name() + "] " + sink.describe()
                        + " 超过 " + softWarnSeconds + " 秒没有进展");
                warn("⚠ " + sink.describe() + " 已 " + (idle / 1000) + " 秒没有进展，"
                        + "其它线程继续在跑。线程堆栈已写入 "
                        + (dump.isEmpty() ? "logs/ 目录（写入失败）" : dump)
                        + " —— 卡死后把这个文件发出来就能定位。");
            }
        }
    }

    private static Writer ensureWriter() {
        if (writer != null) {
            return writer;
        }
        File dir = new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File file = new File(dir, "watchdog.log");
        try {
            if (file.length() > LOG_MAX_BYTES) {
                file.delete();
            }
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8), 8192);
            writtenBytes = file.length();
            lastFlushMillis = System.currentTimeMillis();
            return writer;
        } catch (IOException e) {
            return null;
        }
    }

    private static void flushWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            lastFlushMillis = System.currentTimeMillis();
        } catch (IOException ignored) {
            closeWriter();
        }
    }

    private static void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关不掉就算了，下次 append 重开
        }
        writer = null;
    }
}
