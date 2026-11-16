package updater.fetchdata.tdx.kdata;

import updater.cfg.ConfigException;
import updater.util.FileTool;
import updater.fetchdata.ReadDataException;
import updater.db.DBConnection;
import updater.db.DBException;
import updater.db.DataTableManager;
import updater.fetchdata.tdx.TdxDataDir;
import updater.log.Log;
import updater.mount.MountConfig;
import updater.util.HangWatchdog;
import updater.util.Parallelism;
import updater.util.StageSink;
import updater.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ReaderFromTdx {
    private static ReaderFromTdx  reader= null;
   public static ReaderFromTdx getInstance() throws ConfigException {
       if(reader == null)reader = new ReaderFromTdx();
       return reader;
   }

    private ReaderFromTdx() {
    }

    /**
     * vipdoc 里的 .day 文件名 → 证券代码；不是「个股或场内基金」返回 null
     * （指数、债券、B 股、非 .day 一律跳过）。
     *
     * <p>「补充历史记录」「本地没有库时用本地数据来源文件名当代码来源」「查缺补漏」
     * 都要按同一套规则挑代码，所以这里公开成静态方法，不要在两处各写一份。</p>
     *
     * <p>收哪些：</p>
     * <ul>
     *   <li>沪市：{@code sh6*} 个股，{@code sh5*} 场内基金（510/511/512/513/515/516/518/560/588…）</li>
     *   <li>深市：{@code sz0*} / {@code sz3*}（399 指数除外）个股，{@code sz15*} / {@code sz16*} 基金（159 ETF、16x LOF）</li>
     * </ul>
     *
     * <p>ETF / LOF 和个股在库里是同一套结构（一个代码一个库、同一张 {@code T_D_Day}），
     * 只有价格倍数（基金是×1000）和成交量单位不同，换算见 {@link TdxDayDataReader}。</p>
     */
    public static String codeOf(String prefix, String fileName) {
        int pointIndex = fileName.indexOf(".");
        if(pointIndex== -1) {
            return null;//不处理
        }
        String extension = fileName.substring(pointIndex+1);
        if(!"day".equals(extension)) {//必须是.day文件
            return null;//不处理
        }

        String code = fileName.substring(0, pointIndex);
        if(!code.startsWith(prefix)){//处理指定前缀名
            return null;
        }
        if("sz".equals(prefix)) {//深圳：个股 0/3（399 是指数），基金 15/16
            if(!code.startsWith("sz3")
                    && !code.startsWith("sz0")
                    && !updater.util.SecurityType.isFund(code)) {
                return null;
            }
            if(code.startsWith("sz39")) {
                return null;
            }
        } else if("sh".equals(prefix)) {//上海：个股 6，基金 5
            if(!code.startsWith("sh6")
                    && !updater.util.SecurityType.isFund(code)) {
                return null;
            }
        } else if("bj".equals(prefix)) {//北交所只收个股（目前没有场内基金）
            if(!updater.util.SecurityType.isStock(code)) {
                return null;
            }
        } else {//其它前缀不动
            return null;
        }
        return code;
    }

    /**
     * vipdoc 里的 .day 文件名 → <b>指数</b>代码；不是指数返回 null。
     *
     * <p>跟 {@link #codeOf(String, String)} 是<b>两条互斥的线</b>，不要合并：
     * 那条线刻意跳过指数（指数不能进个股清单、不能参与选股、不能被查缺补漏当成股票对账），
     * 这一条只给「指数数据」任务用 —— 它要的就是那批被跳过的文件。</p>
     *
     * <p>判定口径跟 {@link updater.util.SecurityType#isIndex(String)} 一致：
     * 沪市 {@code sh000xxx}、深市 {@code sz399xxx}、北证 {@code bj899xxx}。
     * 指数和个股共用 6 位代码，靠市场前缀才分得开（{@code sh000001} 是上证指数、
     * {@code sz000001} 是平安银行），所以这里必须带前缀判断。</p>
     */
    public static String indexCodeOf(String prefix, String fileName) {
        if (fileName == null) {
            return null;
        }
        int pointIndex = fileName.indexOf(".");
        if (pointIndex == -1 || !"day".equals(fileName.substring(pointIndex + 1))) {
            return null;
        }
        String code = fileName.substring(0, pointIndex);
        if (!code.startsWith(prefix)) {
            return null;
        }
        return updater.util.SecurityType.isIndex(code) ? code : null;
    }
    private void insertAllData(File[] file, String prefix) throws IOException, ConfigException {
//        TdxDayDataReader reader = new TdxDayDataReader();
        Connection dbconn = null;
        String code = null;
        for(File f:file){
            try {

                String fileName = f.getName();
                 code = codeOf(prefix, fileName);
                if(code == null) {
                    continue;
                }

                File tofile = new File(DBConnection.dbFilePath(code));
                if(tofile.exists()) {//文件存在 处理下一个
                    continue;
                }
                List<TdxStockData> stockDataList = TdxDayDataReader.readAllDay(f.getPath());
//                stockDataList.sort((a, b) -> Integer.compare(b.getDate(),a.getDate()));

                dbconn = DBConnection.connect(code, true);
                dbconn.setAutoCommit(false);
                DataTableManager table = new DataTableManager();
                table.setConn(dbconn);
                table.createDataTable(code);
                for (TdxStockData stockData : stockDataList) {
                    table.insertOrUpdate(stockData, DBConnection.CYCLE_DAY);
                }
                dbconn.commit();

                Log.info(f.getName() + "总记录数: " + stockDataList.size());

            } catch (DBException e) {
                Log.error("warn ############  error: " + e.getMessage());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                // connect() 每只股票新开连接，必须按对象关；close(code) 关不掉它
                DBConnection.closeQuietly(dbconn);
            }

        }
    }

    private void updateCurDay(File[] file, String prefix) throws IOException, ConfigException {
//        TdxDayDataReader reader = new TdxDayDataReader();
        Connection dbconn = null;
        int i = 0;
        long time1 = System.currentTimeMillis();
        for(File f:file){
            try {
                long fileTime = TimeUtil.yyyyMMdd(f.lastModified());
                int workday = TimeUtil.getWorkYyyyMMdd();
                if(fileTime<workday) {//过滤没开盘的股票
                    continue;
                }
                String fileName = f.getName();
                String code = codeOf(prefix, fileName);
                if(code == null) {
                    continue;
                }

                TdxStockData stockData  = TdxDayDataReader.readOneDay(f.getPath());

                dbconn = DBConnection.connect(code);
                DataTableManager table = new DataTableManager();
                table.setConn(dbconn);
//                table.createDataTable(code);

                table.insertOrUpdate(stockData, DBConnection.CYCLE_DAY);
                if(i>0 && i%10==0) {
                    long time2 = System.currentTimeMillis();
                    Log.info("count：10 " + f.getName() + " time:" + (time2 - time1));
                    time1 = time2;
                }

                i++;
            } catch (DBException e) {
                Log.out("warn ############ read sql error: " + e.getMessage());
            }  finally {
                if (dbconn != null) {
                    try {
                        dbconn.close();
                    } catch (SQLException e) {
                        Log.out("warn ##### dbconn close error");
                    }
                }
            }

        }
    }

    public void deleteAll() throws IOException {
       String pathstr = "db";
       File file = new File(pathstr);
        FileTool.deleteDirectoryNIO(file.getAbsolutePath());
    }


    public  void readSzAll(boolean oneORall) throws IOException, ReadDataException, ConfigException {
        String dataPath = new File(new File(TdxDataDir.requireVipdoc(), "sz"), "lday").getPath();
        File[] files =FileTool.listFilesBasic(dataPath);

        if(oneORall) {
//            updateCurDay(file,"sz0");
            updateCurDay(files,"sz");
        } else {
//            saveData(file, "sz0");
            insertAllData(files, "sz");
        }
    }
    public  void readShAll(boolean oneORall) throws IOException, ReadDataException, ConfigException {
        String dataPath = new File(new File(TdxDataDir.requireVipdoc(), "sh"), "lday").getPath();
        File[] files =FileTool.listFilesBasic(dataPath);

        if(oneORall) {
            updateCurDay(files,"sh");
        } else {
            insertAllData(files,"sh");
        }

    }
    /**
     * 「按天数更新」的运行进度。
     *
     * <p>现在是多路并行：{@code processed/created/skipped/failed} 由多个 worker 并发自增，
     * 所以用 {@link AtomicInteger} 而不是 volatile int（volatile int 的 ++ 会丢计数）。
     * 心跳线程只读，用来显示「已经到哪了、最慢那一路在干什么」。</p>
     */
    static final class DayUpdateProgress {
        volatile String market = "";
        volatile int total;
        volatile int workers;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        volatile long startMillis = System.currentTimeMillis();
        volatile boolean done;
        /** 各 worker 的状态槽，界面靠它显示「最慢那一路卡在干什么」 */
        volatile List<StageSink> sinks = java.util.Collections.emptyList();

        String line() {
            long sec = (System.currentTimeMillis() - startMillis) / 1000;
            StringBuilder sb = new StringBuilder(160);
            sb.append('[').append(market).append(' ').append(workers).append(" 路并行] 已处理 ")
                    .append(processed.get()).append('/').append(total).append(" 只")
                    .append("，新建库 ").append(created.get()).append(" 个");
            if (skipped.get() > 0) {
                sb.append("，跳过 ").append(skipped.get()).append(" 只");
            }
            if (failed.get() > 0) {
                sb.append("，失败 ").append(failed.get()).append(" 只");
            }
            sb.append("，已用 ").append(sec).append(" 秒");
            String slow = slowestLine();
            if (!slow.isEmpty()) {
                sb.append("；最慢一路 ").append(slow);
            }
            return sb.toString();
        }

        /** 已经跑了最久的那一只 —— 并行下比「当前在做什么」更有信息量 */
        private String slowestLine() {
            StageSink worst = null;
            long worstMillis = -1L;
            for (StageSink sink : sinks) {
                if (sink.abandoned() || sink.stageId() == StageSink.IDLE) {
                    continue;
                }
                long elapsed = sink.elapsedMillis();
                if (elapsed > worstMillis) {
                    worstMillis = elapsed;
                    worst = sink;
                }
            }
            if (worst == null) {
                return "";
            }
            return worst.describe() + " 已 " + (worstMillis / 1000) + " 秒";
        }
    }

    /**
     * 心跳：每 3 秒把「处理到哪一只、在做什么」打到<b>界面日志</b>和日志文件。
     *
     * <p>为什么需要：空数据文件夹首次跑全市场要逐只建库，Windows 上每建一个
     * SQLite 文件都可能被杀毒/同步盘扫一遍，一只几百毫秒到几秒。原来只在
     * 每 100 只时打一行，第一行要等好几分钟 —— 界面看起来就是「卡死了」。</p>
     *
     * <p>为什么<b>先</b>推界面再写 Log：{@code Log} 是全局单例、每行开关文件，
     * 万一它被磁盘/杀毒卡住，先写 Log 就会连界面一起停住。顺序反过来，
     * 至少界面上一直有「现在在做什么」。</p>
     */
    private static Thread startHeartbeat(final DayUpdateProgress progress,
                                         final java.util.function.Consumer<String> out) {
        final long intervalMillis = progressIntervalSeconds() * 1000L;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!progress.done) {
                    try {
                        Thread.sleep(intervalMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (progress.done) {
                        return;
                    }
                    String line = progress.line();
                    if (out != null) {
                        try {
                            out.accept(line);
                        } catch (Throwable ignored) {
                            // 窗口已经关掉就当没有，心跳不能因为这个停
                        }
                    }
                    try {
                        Log.info(line);
                    } catch (Throwable ignored) {
                        // 日志文件写不出去不影响更新
                    }
                }
            }
        }, "tdx-update-heartbeat");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 进度输出：有界面出口就只给界面（+ 独立诊断文件），不再经过 {@code Log}。
     *
     * <p>盘后下载卡死那次排查下来，{@code Log} 这条链上任何一处 IO 阻塞（日志文件被
     * 同步盘/杀毒占着、控制台管道没人读）都会把调用它的任务线程一起拖住。更新主循环
     * 用惯了 Log，一旦日志卡住就表现成「db 不再生成、界面也没有新行」—— 所以主循环的
     * 进度改走这里，彻底把「日志 IO」从「任务推进」上摘开。</p>
     */
    private static void report(java.util.function.Consumer<String> out, String message) {
        HangWatchdog.logLine(message);
        if (out != null) {
            try {
                out.accept(message);
                return;
            } catch (Throwable ignored) {
                // 界面没了就退回日志
            }
        }
        try {
            Log.info(message);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    public void updateDataByDays(int days) throws IOException, ReadDataException, ConfigException {
        updateDataByDays(days, null);
    }

    /**
     * 按天数更新全市场日线（多路并行）。
     *
     * @param out 进度出口（更新画面的日志区），可为 null。给了就每几秒推一行
     *            「几路并行、已处理多少、最慢那一路卡在干什么」，界面不会干等。
     */
    public void updateDataByDays(int days, java.util.function.Consumer<String> out)
            throws IOException, ReadDataException, ConfigException {
        updateDataByDays(days, out, null, null, null);
    }

    /**
     * 带逐只台账的版本：每只股票开始 / 成功 / 跳过 / 失败都报给 {@code items}，
     * 更新画面靠它给每一只留一行，中断后还能接着跑。
     *
     * @param items     逐只进度，null 表示不需要
     * @param cancel    停止标志，非空时 worker 领下一只前会检查，收到停止就收手
     * @param onlyCodes 只处理这些代码（续跑 / 重试失败），null = 全市场
     */
    public void updateDataByDays(int days, java.util.function.Consumer<String> out,
                                 updater.util.ItemProgress items,
                                 java.util.concurrent.atomic.AtomicBoolean cancel,
                                 java.util.Collection<String> onlyCodes)
            throws IOException, ReadDataException, ConfigException {
        final updater.util.ItemProgress sink = items == null
                ? updater.util.ItemProgress.NONE : items;
        DayUpdateProgress progress = new DayUpdateProgress();
        HangWatchdog.start("盘后数据下载（最近 " + days + " 天）", out);
        Thread heartbeat = startHeartbeat(progress, out);
        int sh;
        int sz;
        try {
            report(out, "开始更新上海市场最近 " + days + " 天的数据");
            sh = updateDataByDaysForPrefix("sh", days, progress, out, sink, cancel, onlyCodes);

            if (cancel != null && cancel.get()) {
                report(out, "收到停止请求，深圳市场不再开始。已完成的部分都在台账里。");
                sz = 0;
            } else {
                report(out, "开始更新深圳市场最近 " + days + " 天的数据");
                sz = updateDataByDaysForPrefix("sz", days, progress, out, sink, cancel, onlyCodes);
            }
        } finally {
            progress.done = true;
            heartbeat.interrupt();
            HangWatchdog.stop();
        }

        report(out, "数据更新完成");
        if (sh + sz > 0) {
            // 盘后更新同样是「价格数据刚写完」，脚本按这个时间判断当天K线是否完整
            updater.fetchdata.PriceUpdateClock.markUpdated();
            // 首页检索前要问「今天跑过盘后下载没」：这个标记只能由盘后下载链路写，
            // 不能拿 price_update.txt 顶替（网络抓价也会写它）
            updater.fetchdata.AfterHoursDownloadClock.markToday();
        }
    }

    /**
     * 单只股票在同一阶段停这么久就跳过它（秒），改 config.properties 的 updater.stock.timeout.seconds。
     *
     * <p>这是兜底、不是给用户看的：触发时只抓堆栈写 logs、并让其余路继续，
     * 界面不弹任何东西（真卡住时界面已经有看门狗的「已 N 秒没有进展」）。</p>
     */
    private static int stockTimeoutSeconds() {
        try {
            int value = updater.cfg.AppConfigManager.getInstance().getConfigReader()
                    .getInt("updater.stock.timeout.seconds", 120);
            return Math.max(30, value);
        } catch (Throwable e) {
            return 120;
        }
    }

    /** 进度推给界面的间隔（秒） */
    private static int progressIntervalSeconds() {
        try {
            int value = updater.cfg.AppConfigManager.getInstance().getConfigReader()
                    .getInt("updater.progress.interval.seconds", 3);
            return Math.max(1, value);
        } catch (Throwable e) {
            return 3;
        }
    }

    /** 是否只读 .day 尾部 N 条（默认是；关掉就退回「读整个文件再取尾」的老做法） */
    private static volatile Boolean tailReadCache;

    private static boolean tailRead() {
        Boolean cached = tailReadCache;
        if (cached == null) {
            boolean value = true;
            try {
                value = updater.cfg.AppConfigManager.getInstance().getConfigReader()
                        .getBoolean("updater.day.read.tail", true);
            } catch (Throwable ignored) {
                // 读不到配置就用默认值，不能因为读配置把更新拖住
            }
            cached = Boolean.valueOf(value);
            tailReadCache = cached;
        }
        return cached.booleanValue();
    }

    /**
     * 一个 worker 的统计：各阶段耗时、单只耗时直方图、慢例、错误。
     *
     * <p>只被自己的线程写，任务结束时由主线程合并 —— 整个类不需要任何锁。</p>
     *
     * <p><b>为什么要有它</b>：这条链路 90% 的时间都在等磁盘（SQLite 提交要 fsync），
     * 靠猜永远猜不准。跑一轮就把「读文件/开库/建表/写库/提交/关库」各占多少秒打出来，
     * 下次再觉得慢，看表就行。</p>
     */
    static final class WorkerStats {
        static final String[] NAMES = {"读文件", "开库", "建表", "写库", "提交", "关库"};
        /** 单只耗时直方图，桶宽 1 毫秒，最后一桶表示「≥2 秒」 */
        private static final int HISTOGRAM_BUCKETS = 2001;

        final long[] stageNanos = new long[NAMES.length];
        long totalNanos;
        int stocks;
        int created;

        private final int[] histogram = new int[HISTOGRAM_BUCKETS];
        private final List<String> errors = new ArrayList<String>();
        private final List<Long> slowNanos = new ArrayList<Long>();
        private final List<String> slowCodes = new ArrayList<String>();

        void addRead(long n) {
            stageNanos[0] += n;
        }

        void addOpen(long n) {
            stageNanos[1] += n;
        }

        void addDdl(long n) {
            stageNanos[2] += n;
        }

        void addWrite(long n) {
            stageNanos[3] += n;
        }

        void addCommit(long n) {
            stageNanos[4] += n;
        }

        void addClose(long n) {
            stageNanos[5] += n;
        }

        /** 记录一只的总耗时；超过 3 秒的留作「慢例」，结束时单独列出来 */
        void record(long nanos, String code) {
            stocks++;
            totalNanos += nanos;
            long ms = nanos / 1000000L;
            int bucket = ms >= HISTOGRAM_BUCKETS ? HISTOGRAM_BUCKETS - 1 : (int) ms;
            histogram[bucket]++;
            if (ms >= 3000) {
                slowNanos.add(Long.valueOf(nanos));
                slowCodes.add(code);
            }
        }

        void addError(String code, Throwable t) {
            if (errors.size() < 20) {
                errors.add(code + "：" + t);
            }
        }

        void merge(WorkerStats other) {
            if (other == null) {
                return;
            }
            for (int i = 0; i < stageNanos.length; i++) {
                stageNanos[i] += other.stageNanos[i];
            }
            totalNanos += other.totalNanos;
            stocks += other.stocks;
            created += other.created;
            for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
                histogram[i] += other.histogram[i];
            }
            errors.addAll(other.errors);
            slowNanos.addAll(other.slowNanos);
            slowCodes.addAll(other.slowCodes);
        }

        /** 分阶段耗时汇总：哪个阶段占大头一眼就能看出来 */
        String[] summary() {
            if (stocks == 0) {
                return new String[]{"没有处理任何股票，没有耗时统计"};
            }
            long sum = 0L;
            for (long n : stageNanos) {
                sum += n;
            }
            StringBuilder sb = new StringBuilder(200);
            sb.append("分阶段耗时（合计 ").append(seconds(sum)).append(" 秒）：");
            for (int i = 0; i < NAMES.length; i++) {
                if (i > 0) {
                    sb.append(" / ");
                }
                sb.append(NAMES[i]).append(' ').append(seconds(stageNanos[i])).append(" 秒");
                if (sum > 0) {
                    sb.append("（").append(stageNanos[i] * 100 / sum).append("%）");
                }
            }
            return new String[]{
                    sb.toString(),
                    "单只耗时：平均 " + (totalNanos / stocks / 1000000L) + " 毫秒，"
                            + "p50 " + percentile(50) + " 毫秒，"
                            + "p90 " + percentile(90) + " 毫秒，"
                            + "p99 " + percentile(99) + " 毫秒"
            };
        }

        /** 最慢的几只（只在超过 3 秒的里面挑），最多 limit 条 */
        List<String> slowestLines(int limit) {
            List<String> lines = new ArrayList<String>();
            int size = slowNanos.size();
            if (size == 0) {
                return lines;
            }
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) {
                order[i] = Integer.valueOf(i);
            }
            final List<Long> nanos = slowNanos;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Long.compare(nanos.get(b.intValue()).longValue(),
                            nanos.get(a.intValue()).longValue());
                }
            });
            int n = Math.min(limit, size);
            for (int i = 0; i < n; i++) {
                int idx = order[i].intValue();
                lines.add("慢例：" + slowCodes.get(idx) + " 用了 "
                        + seconds(slowNanos.get(idx).longValue()) + " 秒");
            }
            return lines;
        }

        private int percentile(int p) {
            long total = 0L;
            for (int v : histogram) {
                total += v;
            }
            long target = Math.max(1L, total * p / 100L);
            long seen = 0L;
            for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
                seen += histogram[i];
                if (seen >= target) {
                    return i;
                }
            }
            return HISTOGRAM_BUCKETS - 1;
        }

        String[] errorLines() {
            return errors.toArray(new String[0]);
        }

        private static String seconds(long nanos) {
            return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e9);
        }
    }

    /** 一只股票要处理的活（代码在排队时就解析好，worker 里不再重复截字符串） */
    static final class Job {
        final String code;
        final File file;

        Job(String code, File file) {
            this.code = code;
            this.file = file;
        }
    }

    private int updateDataByDaysForPrefix(String prefix, int days, DayUpdateProgress progress,
                                          java.util.function.Consumer<String> out,
                                          updater.util.ItemProgress items,
                                          java.util.concurrent.atomic.AtomicBoolean cancel,
                                          java.util.Collection<String> onlyCodes)
            throws IOException, ConfigException, ReadDataException {
        String dataPath = new File(new File(TdxDataDir.requireVipdoc(), prefix), "lday").getPath();
        File[] files = FileTool.listFilesBasic(dataPath);
        if (files == null) {
            report(out, "目录不存在或为空: " + dataPath);
            return 0;
        }

        // 先把活排好队：一次遍历挑出个股 .day，worker 从共享游标领，互不干扰
        List<Job> jobs = new ArrayList<Job>(files.length);
        List<String> keys = new ArrayList<String>(files.length);
        for (File f : files) {
            String code = codeOf(prefix, f.getName());
            if (code == null) {
                continue;
            }
            if (onlyCodes != null && !onlyCodes.contains(code)) {
                continue;
            }
            jobs.add(new Job(code, f));
            keys.add(code);
        }
        // 台账：先把这一批的清单铺成「未开始」，用户一眼能看到要做多少只
        items.planned(keys);
        final List<Job> tasks = java.util.Collections.unmodifiableList(jobs);
        final int total = tasks.size();
        final int workers = Math.min(Parallelism.resolve(), Math.max(1, total));
        final int timeoutSeconds = stockTimeoutSeconds();

        progress.market = prefix.toUpperCase();
        progress.total = total;
        progress.workers = workers;
        progress.processed.set(0);
        progress.created.set(0);
        progress.skipped.set(0);
        progress.failed.set(0);
        progress.startMillis = System.currentTimeMillis();

        report(out, prefix.toUpperCase() + " 市场：目录里 " + files.length + " 个文件，个股 " + total
                + " 只，" + Parallelism.describe(workers));
        if (total == 0) {
            return 0;
        }

        final AtomicInteger cursor = new AtomicInteger(0);
        final AtomicInteger live = new AtomicInteger(workers);
        final List<StageSink> sinks = new ArrayList<StageSink>(workers);
        final WorkerStats[] stats = new WorkerStats[workers];

        for (int i = 0; i < workers; i++) {
            final int id = i;
            final StageSink sink = HangWatchdog.newSink("线程" + (i + 1));
            sinks.add(sink);
            final WorkerStats stat = new WorkerStats();
            stats[i] = stat;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWorker(prefix, days, tasks, cursor, progress, sink, stat, live, items, cancel);
                }
            }, "tdx-" + prefix + "-w" + (i + 1));
            thread.setDaemon(true);
            thread.start();
        }
        progress.sinks = sinks;

        // ---- 主线程只做监视：判定僵死 + 定时推进度 ----
        // worker 一律不打印、不碰界面，所以并行下日志不会乱序，也没有日志锁竞争。
        long lastReport = System.currentTimeMillis();
        while (live.get() > 0) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();

            for (int i = 0; i < workers; i++) {
                StageSink sink = sinks.get(i);
                if (sink.abandoned() || sink.stageId() == StageSink.IDLE) {
                    continue;
                }
                // 刚进入新阶段的不算；停超过阈值才判定「这一只挂在 native 里了」
                if (sink.idleMillis() < timeoutSeconds * 1000L) {
                    continue;
                }
                String code = sink.code();
                String stageText = sink.stageText();
                sink.abandon();
                live.decrementAndGet();
                progress.skipped.incrementAndGet();
                // 卡死的这一只也要在台账里留名，否则用户不知道是哪只没进去
                items.fail(code, "停在「" + stageText + "」超过 " + timeoutSeconds
                        + " 秒没有返回，已跳过这一只（详见 logs 下的 hang-*.log）");
                // 兜底动作，不是需要用户处理的事件：只在 logs 留证据，不推界面。
                // 真卡住时用户想要的是「别干等着」，而不是先读一句技术提示。
                HangWatchdog.dumpThreads("股票 " + code + " 停在「" + stageText + "」超过 "
                        + timeoutSeconds + " 秒没有返回，已跳过这一只");

                if (live.get() <= 0 && cursor.get() < total) {
                    // 队列里还有股票没人领，而已经没有人干活了 —— 这是环境问题，必须说出来
                    report(out, "⚠ 所有并行线程都卡在磁盘上，队列里还有 " + (total - cursor.get())
                            + " 只没处理，本次提前结束。请把 logs 目录下的 hang-*.log 发出来，"
                            + "并换一个本地盘文件夹再试。");
                }
            }

            if (now - lastReport >= progressIntervalSeconds() * 1000L) {
                lastReport = now;
                report(out, progress.line());
            }
        }

        for (StageSink sink : sinks) {
            HangWatchdog.removeSink(sink);
        }

        WorkerStats merged = new WorkerStats();
        for (WorkerStats stat : stats) {
            merged.merge(stat);
        }
        int processed = progress.processed.get();
        int createdTotal = progress.created.get();
        int skipped = progress.skipped.get();
        int failed = progress.failed.get();

        report(out, prefix.toUpperCase() + " 市场处理完成：处理 " + processed + " 只，新建库 "
                + createdTotal + " 个"
                + (skipped > 0 ? "，跳过 " + skipped + " 只" : "")
                + (failed > 0 ? "，失败 " + failed + " 只" : ""));
        for (String line : merged.summary()) {
            report(out, line);
        }
        for (String line : merged.errorLines()) {
            report(out, "  失败明细: " + line);
        }
        for (String line : merged.slowestLines(5)) {
            report(out, line);
        }
        return processed;
    }

    /** 单个 worker 的主循环：从共享游标领一只、处理、再领下一只 */
    private void runWorker(String prefix, int days, List<Job> tasks, AtomicInteger cursor,
                           DayUpdateProgress progress, StageSink sink, WorkerStats stats,
                           AtomicInteger live, updater.util.ItemProgress items,
                           java.util.concurrent.atomic.AtomicBoolean cancel) {
        try {
            while (true) {
                // 停止请求：领下一只之前收手，剩下的保持「未开始」，下次可以接着跑
                if (cancel != null && cancel.get()) {
                    return;
                }
                int index = cursor.getAndIncrement();
                if (index >= tasks.size()) {
                    return;
                }
                Job job = tasks.get(index);
                sink.begin(job.code);
                items.begin(job.code);
                long start = System.nanoTime();
                int[] rows = new int[1];
                try {
                    int result = processOne(job.code, job.file, days, sink, stats, rows);
                    if (result >= 0) {
                        progress.processed.incrementAndGet();
                        if (result == 1) {
                            progress.created.incrementAndGet();
                        }
                        items.done(job.code, (result == 1 ? "新建库 · " : "")
                                + "写入 " + rows[0] + " 根日线");
                    } else {
                        // 本地数据来源里这段时间没有数据（新股 / 长期停牌），不是错误
                        progress.skipped.incrementAndGet();
                        items.skip(job.code, "本地数据来源里没有最近 " + days + " 天的数据");
                    }
                } catch (Throwable t) {
                    // 单只出问题（库被占用、文件损坏、磁盘满）不能让整轮更新停住。
                    // 这里必须接 Throwable —— DBConnection 把 SQLException 包成了 RuntimeException。
                    progress.failed.incrementAndGet();
                    stats.addError(job.code, t);
                    items.fail(job.code, t.getMessage() == null ? t.toString() : t.getMessage());
                } finally {
                    sink.finish();
                    stats.record(System.nanoTime() - start, job.code);
                }
            }
        } finally {
            // 正常退出还是异常退出都要摘掉自己，否则主线程会一直等下去
            live.decrementAndGet();
        }
    }

    /**
     * 处理一只股票：读本地数据来源最近 N 天 → 开库 → 建表 → 写入 → 提交 → 关库。
     *
     * <p>每个阶段打一次 {@link StageSink#stage(int)}（只写一个 volatile int，纳秒级），
     * 卡住时快照里能直接看到「停在读文件 / 停在开库 / 停在提交」。
     * 各阶段耗时累加进 {@link WorkerStats}，任务结束打一张表。</p>
     *
     * @return 1 = 新建了库，0 = 更新了已有库，-1 = 本地数据来源里没数据、跳过
     */
    private int processOne(String code, File file, int days, StageSink sink, WorkerStats stats,
                           int[] rowsOut)
            throws Exception {
        boolean existed = new File(DBConnection.dbFilePath(code)).isFile();

        long t0 = System.nanoTime();
        sink.stage(StageSink.READ);
        List<TdxStockData> recent = readRecentDays(file.getPath(), days);
        long t1 = System.nanoTime();
        stats.addRead(t1 - t0);

        if (recent.isEmpty()) {
            return -1;
        }
        rowsOut[0] = recent.size();

        Connection dbconn = null;
        try {
            sink.stage(StageSink.OPEN);
            dbconn = DBConnection.connect(code, true);
            dbconn.setAutoCommit(false);
            long t2 = System.nanoTime();
            stats.addOpen(t2 - t1);

            sink.stage(StageSink.DDL);
            DataTableManager table = new DataTableManager();
            table.setConn(dbconn);
            table.ensureDataTable(code, existed);
            long t3 = System.nanoTime();
            stats.addDdl(t3 - t2);

            sink.stage(StageSink.WRITE);
            table.insertBatch(recent);
            long t4 = System.nanoTime();
            stats.addWrite(t4 - t3);

            sink.stage(StageSink.COMMIT);
            dbconn.commit();
            long t5 = System.nanoTime();
            stats.addCommit(t5 - t4);

            sink.stage(StageSink.CLOSE);
            DBConnection.closeQuietly(dbconn);
            dbconn = null;
            long t6 = System.nanoTime();
            stats.addClose(t6 - t5);

            if (!existed) {
                stats.created++;
                return 1;
            }
            return 0;
        } finally {
            DBConnection.closeQuietly(dbconn);
        }
    }

    /** 最近 N 天：默认只读 .day 尾部 N 条；关掉 updater.day.read.tail 就退回读全量再取尾 */
    private List<TdxStockData> readRecentDays(String filePath, int days) throws IOException {
        if (tailRead()) {
            return TdxDayDataReader.readLastDays(filePath, days);
        }
        List<TdxStockData> allData = TdxDayDataReader.readAllDay(filePath);
        if (allData.size() <= days) {
            return allData;
        }
        // 返回最近days天的数据（假设数据按日期升序排列，最新数据在最后）
        return allData.subList(allData.size() - days, allData.size());
    }

    // ================================================================ 补充历史记录

    /** 「补充历史记录」的结果统计 */
    public static class HistoryResult {
        /** 本地数据来源里认得出的个股日线文件数 */
        public int scannedStocks;
        /** 实际补进数据的股票数 */
        public int updatedStocks;
        /** 其中本地原来没有库、这次新建的库数 */
        public int createdStocks;
        /** 实际补进去的日线条数 */
        public int rowsInserted;
        /** 本地数据来源里最早 / 最晚的日期（yyyyMMdd），没有数据时为 0 */
        public int tdxFirstDate;
        public int tdxLastDate;
        /** 本地库目录 */
        public String dbDir = "";
        public long elapsedMillis;
    }

    /** 补充历史前先确认目标目录：没有库时「补」无可补，直接给结论，不要空跑一遍全市场 */
    public static final String NO_DB_MESSAGE =
            "本地还没有股票数据库，无法补充历史记录。\n"
                    + "请先执行「更新数据-补最新价格」或「更新数据-盘后数据下载」把数据库建起来"
                    + "（并更新到最新一天），再回来补充历史。";

    /**
     * 本地数据来源的 vipdoc 下的 .day 文件把<b>全部历史</b>并入本地已有库。
     *
     * <p>本地数据来源里的日线一般留着 5~10 年，而「盘后数据下载」只按天数取最近 N 天、
     * 「补最新价格」新建的库更是只有一根日K —— 这个任务就是补这个缺口。</p>
     *
     * <p>口径：
     * <ul>
     *   <li>只补本地没有的日期（{@code T_D_Day} 里已存在的 TIME 一律跳过），已有数据不覆盖</li>
     *   <li>本地没有库的<b>顺手建库</b>：场内基金（ETF/LOF）第一次进来全走这条，
     *       用户手工删掉的库也会在这里补回来</li>
     *   <li>单只股票失败只记日志、继续下一只，不整批中止</li>
     *   <li>不更新「价格数据更新时间」：历史回填不代表当天行情走完</li>
     * </ul>
     *
     * @param log 进度输出，null 表示打到控制台
     * @throws IllegalStateException 本地还没有股票库
     */
    public HistoryResult importAllHistory(final java.util.function.Consumer<String> log)
            throws IOException, ConfigException, DBException, ReadDataException {
        return importAllHistory(log, null, null, null);
    }

    /**
     * 带逐只台账的版本（更新画面用）。
     *
     * @param items     逐只进度，null 表示不需要
     * @param cancel    停止标志，可为 null
     * @param onlyCodes 只处理这些代码（续跑 / 重试），null = 全市场
     */
    public HistoryResult importAllHistory(final java.util.function.Consumer<String> log,
                                          updater.util.ItemProgress items,
                                          java.util.concurrent.atomic.AtomicBoolean cancel,
                                          java.util.Collection<String> onlyCodes)
            throws IOException, ConfigException, DBException, ReadDataException {
        final java.util.function.Consumer<String> out =
                log == null ? System.out::println : log;
        final updater.util.ItemProgress itemProgress = items == null
                ? updater.util.ItemProgress.NONE : items;

        String dbDir = MountConfig.currentDbDir();
        if (dbDir.isEmpty() || !MountConfig.hasStockData()) {
            throw new IllegalStateException(NO_DB_MESSAGE);
        }
        File vipdoc = TdxDataDir.requireVipdoc();

        HistoryResult result = new HistoryResult();
        result.dbDir = dbDir;
        long start = System.currentTimeMillis();
        out.accept("本地数据来源目录: " + vipdoc.getAbsolutePath());
        out.accept("本地股票库目录: " + dbDir);

        HangWatchdog.start("补充历史记录", out);
        StageSink sink = HangWatchdog.newSink("补历史");
        try {
            importHistoryForPrefix("sh", vipdoc, result, out, sink, itemProgress, cancel, onlyCodes);
            if (cancel == null || !cancel.get()) {
                importHistoryForPrefix("sz", vipdoc, result, out, sink, itemProgress, cancel, onlyCodes);
            } else {
                out.accept("收到停止请求，深圳市场不再开始。已完成的部分都在台账里。");
            }
        } finally {
            HangWatchdog.removeSink(sink);
            HangWatchdog.stop();
        }

        result.elapsedMillis = System.currentTimeMillis() - start;
        String summary = "补充历史完成：本地数据来源 " + result.scannedStocks + " 只，"
                + "补入 " + result.updatedStocks + " 只 / " + result.rowsInserted + " 条"
                + (result.createdStocks > 0 ? "（其中新建库 " + result.createdStocks + " 个）" : "")
                + "，用时 " + (result.elapsedMillis / 1000) + " 秒";
        out.accept(summary);
        Log.info(summary);
        return result;
    }

    private void importHistoryForPrefix(String prefix, File vipdoc, HistoryResult result,
                                        final java.util.function.Consumer<String> out, StageSink sink,
                                        updater.util.ItemProgress items,
                                        java.util.concurrent.atomic.AtomicBoolean cancel,
                                        java.util.Collection<String> onlyCodes)
            throws IOException, ReadDataException {
        File dir = new File(new File(vipdoc, prefix), "lday");
        File[] files = FileTool.listFilesBasic(dir.getPath());
        if (files == null || files.length == 0) {
            out.accept("目录不存在或为空，跳过: " + dir.getAbsolutePath());
            return;
        }
        out.accept(prefix.toUpperCase() + " 市场: " + files.length + " 个日线文件，开始补历史");

        // 先铺台账清单（只含真正要处理的个股，指数/基金/债券不算）
        java.util.List<String> keys = new ArrayList<String>();
        for (File file : files) {
            String code = codeOf(prefix, file.getName());
            if (code == null) {
                continue;
            }
            if (onlyCodes != null && !onlyCodes.contains(code)) {
                continue;
            }
            keys.add(code);
        }
        items.planned(keys);

        long tick = System.currentTimeMillis();
        int index = 0;
        for (File file : files) {
            if (cancel != null && cancel.get()) {
                return;
            }
            index++;
            String code = codeOf(prefix, file.getName());
            if (code == null) {
                continue;
            }
            if (onlyCodes != null && !onlyCodes.contains(code)) {
                continue;
            }
            result.scannedStocks++;

            // 本地没有这只的库：这回顺手建出来。
            // 场内基金（ETF/LOF）以前一个库都没有，全靠这一步落地；顺带也能补回用户手工删掉的库。
            File dbFile = new File(result.dbDir, code + ".db");
            boolean createNew = !dbFile.isFile();

            Connection conn = null;
            sink.begin(code);
            items.begin(code);
            try {
                sink.stage(StageSink.READ);
                List<TdxStockData> all = TdxDayDataReader.readAllDay(file.getPath());
                if (all.isEmpty()) {
                    items.skip(code, "本地数据来源的日线文件里没有数据");
                    continue;
                }
                trackRange(result, all);

                sink.stage(StageSink.OPEN);
                conn = DBConnection.connect(code, createNew);
                DataTableManager table = new DataTableManager();
                table.setConn(conn);
                // 新建的库、以及用户手工建过的空壳库（有文件没表），先保证表在
                table.ensureDataTable(code, !createNew);

                java.util.Set<Long> existing = loadExistingTimes(conn);
                List<TdxStockData> missing = new ArrayList<>(all.size());
                for (TdxStockData data : all) {
                    if (!existing.contains(Long.valueOf(data.getDate()))) {
                        missing.add(data);
                    }
                }
                if (missing.isEmpty()) {
                    items.skip(code, "本地已是最全，没有要补的日期");
                    continue;
                }

                sink.stage(StageSink.WRITE);
                conn.setAutoCommit(false);
                int rows = table.insertBatch(missing);
                sink.stage(StageSink.COMMIT);
                conn.commit();

                result.updatedStocks++;
                result.rowsInserted += rows;
                if (createNew) {
                    result.createdStocks++;
                }
                items.done(code, createNew
                        ? "新建库，写入 " + rows + " 条"
                        : "补入 " + rows + " 条（原有 " + existing.size() + " 条）");
            } catch (DBException | SQLException e) {
                rollbackQuietly(conn);
                String message = "  " + code + " 补充失败: " + e.getMessage();
                out.accept(message);
                Log.error(message);
                items.fail(code, e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Throwable t) {
                // 读文件 IO 异常等：不打断整批
                out.accept("  " + code + " 补充失败: " + t);
                items.fail(code, t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                sink.finish();
                DBConnection.closeQuietly(conn);
            }

            if (index % 200 == 0) {
                long now = System.currentTimeMillis();
                out.accept(prefix.toUpperCase() + " 进度 " + index + "/" + files.length
                        + "，已补 " + result.rowsInserted + " 条，本批 200 只用时 "
                        + ((now - tick) / 1000) + " 秒");
                tick = now;
            }
        }
        out.accept(prefix.toUpperCase() + " 市场补历史结束：" + index + " 个文件");
    }

    /** 库里已有的日期（一只股票几千行，一次读进来比逐条查快得多） */
    private static java.util.Set<Long> loadExistingTimes(Connection conn) throws SQLException {
        java.util.Set<Long> times = new java.util.HashSet<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT TIME FROM T_D_Day");
            while (rs.next()) {
                times.add(Long.valueOf(rs.getLong(1)));
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响已经读到的数据
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                    // 同上
                }
            }
        }
        return times;
    }

    private static void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败（连接已坏）时下面会直接关连接
        }
    }

    /** 记录本地数据来源里出现过的最早 / 最晚日期，汇总时告诉用户「本地数据来源里有多少年」 */
    private static void trackRange(HistoryResult result, List<TdxStockData> all) {
        int first = all.get(0).getDate();
        int last = all.get(all.size() - 1).getDate();
        if (result.tdxFirstDate == 0 || first < result.tdxFirstDate) {
            result.tdxFirstDate = first;
        }
        if (last > result.tdxLastDate) {
            result.tdxLastDate = last;
        }
    }
}
