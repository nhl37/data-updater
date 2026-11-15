package updater.main.updater;

import updater.db.DBConnection;
import updater.db.DataTableManager;
import updater.db.DivdendTableManager;
import updater.fetchdata.min.Min5Online;
import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.fetchdata.tdx.kdata.TdxDayDataReader;
import updater.fetchdata.tdx.kdata.TdxStockData;
import updater.fetchdata.tdx.min.MinDataUpdater;
import updater.fetchdata.tdx.min.TdxMinBar;
import updater.fetchdata.tdx.min.TdxMinDataReader;
import updater.log.Log;
import updater.mount.MountConfig;
import updater.util.FileTool;
import updater.util.ItemProgress;
import updater.util.Parallelism;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 查缺补漏：把「本地数据来源里的日线」当唯一真值，逐只跟本地库对账；顺带核 5 分钟线有没有落后。
 *
 * <p><b>为什么要有它</b>：盘后下载只拉最近 N 天、补历史只处理「本地数据来源里有的证券」，
 * 于是现实里会攒下一堆没人发现的窟窿 —— 某只股票本地库整个没了、
 * 某只库是 0 字节空壳、某只中间缺了三个月、某只停在半年前。
 * 这些股票在选股时要么直接消失，要么算出错的均线，界面上完全看不出来。</p>
 *
 * <p><b>为什么连 5 分钟线一起查</b>：分钟线走的是另一条链路（{@code T_D_Min5} 表），
 * 以前只在「分钟数据」那个任务里补，日线对账再完整也看不出分钟线停在几天前 ——
 * 脚本里 {@code FIND30MIN / HAS30MIN} 那类函数于是静默少数据。分钟线只判「末根时间落后于本地数据来源」，
 * 本地数据来源分钟文件只滚动 6 个月，逐根对比没有意义。</p>
 *
 * <p>这个类只做两件事：{@link #scan} 对账并逐只给出结论（进台账）；
 * {@link #repair} 按对账结论补数据（缺库就建库、缺日期就补那几根、缺表就补表）。
 * 两者都不碰界面，进度一律走 {@link ItemProgress}。</p>
 */
public final class DataIntegrityChecker {

    /** 根目录读不出来时给用户看的原话 */
    private static final String NO_DATA_DIR = "还没有挂载股票数据文件夹";

    /** 最近多少个交易日参与「分钟线有没有整天断」的核对 */
    private static final int RECENT_DAYS = 10;

    private DataIntegrityChecker() {
    }

    /** 一次对账 / 一次修复的汇总，用来写结果行 */
    public static final class Summary {
        /** 本次要处理的股票数 */
        public int total;
        /** 完整、不需要动的 */
        public int ok;
        /** 本地根本没有库 */
        public int missingDb;
        /** 库文件打不开 */
        public int broken;
        /** 库在、但数据不全（缺日期 / 缺表 / 5 分钟线落后） */
        public int gap;
        /** 只在修复时有意义：补进库的日线根数 */
        public long rowsWritten;
        /** 只在修复时有意义：补进库的 5 分钟根数 */
        public long minRowsWritten;
        /** 缺口合计根数（扫描时算出） */
        public long missingBars;
        /** 5 分钟线落后的只数（扫描时算出；含在各类问题里，不单独加总） */
        public int minBehind;
        /** 本次真正核过 5 分钟线的只数（有日线库就核，不再要求本地有分钟文件） */
        public int minChecked;
        /** 没有本地分钟文件、只能走在线线路补的只数 */
        public int minNeedOnline;
        /** 在线线路补进去的 5 分钟根数 */
        public long minOnlineRows;
        public long elapsedMs;

        public String describeScan() {
            StringBuilder sb = new StringBuilder();
            sb.append("扫描 ").append(total).append(" 只：完整 ").append(ok);
            int bad = missingDb + broken + gap;
            if (bad == 0) {
                sb.append("，没有发现缺失");
            } else {
                sb.append("，有问题 ").append(bad).append(" 只（缺库 ").append(missingDb)
                        .append("、打不开 ").append(broken).append("、数据不全 ").append(gap)
                        .append("），共缺 ").append(missingBars).append(" 根日线");
                if (minBehind > 0) {
                    sb.append("；其中 5 分钟线待补 ").append(minBehind).append(" 只");
                }
            }
            if (minChecked > 0) {
                sb.append("；5 分钟线已核 ").append(minChecked).append(" 只");
                if (minNeedOnline > 0) {
                    sb.append("（").append(minNeedOnline).append(" 只没有本地分钟文件，修复时走在线线路）");
                }
            }
            sb.append(" · 用时 ").append(formatDuration(elapsedMs));
            if (bad > 0) {
                sb.append("（点「修复缺失」按台账补齐）");
            }
            return sb.toString();
        }

        public String describeRepair() {
            return "修复完成：处理好 " + ok + " 只、补入 " + rowsWritten + " 根日线"
                    + (minRowsWritten > 0 ? "、" + minRowsWritten + " 根 5 分钟线" : "")
                    + (minOnlineRows > 0 ? "（其中在线线路补 " + minOnlineRows + " 根）" : "")
                    + " · 用时 " + formatDuration(elapsedMs);
        }
    }

    /** 一只股票的日线文件 + 代码 */
    private static final class Job {
        final String code;
        final File file;

        Job(String code, File file) {
            this.code = code;
            this.file = file;
        }
    }

    /**
     * 一只股票的对账结果。
     *
     * <p>{@code data} 是本地数据来源里「范围内」的全部日线，修复时直接从这里挑要补的几根，
     * 不再重新读一遍文件（全市场几千只，重读一次要多花好几分钟）。</p>
     */
    private static final class Inspection {
        String code;
        File dbFile;
        boolean dbExists;
        boolean dbBroken;
        String brokenReason = "";
        /** 库在，但没有 T_D_Day / T_Divdend 表 */
        boolean needTable;
        boolean needDivTable;
        int tdxBars;
        int localBars;
        long firstTdx;
        long lastTdx;
        long lastLocal;
        /** 缺的日期（升序） */
        final List<Long> missing = new ArrayList<Long>();
        /** 缺的日期分几段（中间断档算多段） */
        int segments;
        /** 末尾连续缺了多少根（「本地停在半年前」就是它） */
        int tailMissing;
        /** 范围内本地数据来源的全部日线 */
        List<TdxStockData> data = Collections.emptyList();
        /** 本地数据来源分钟线文件最后一根的时刻（yyyyMMddHHmm）；0 = 本地数据来源没有这只的分钟文件 */
        long tdxLastMin;
        /** 本地 T_D_Min5 最后一根时刻；0 = 没有表或表是空的 */
        long localLastMin;
        /** 本地 5 分钟线落后：没表 / 空表 / 停在更早的时刻 / 最近几天整天缺 */
        boolean needMin;
        /** 这只参与了 5 分钟线核对 */
        boolean minChecked;
        /** 本地数据来源没有这只的分钟文件，只能走在线线路补 */
        boolean minNeedOnline;
        /** 最近几个交易日里分钟线一根都没有的天数 */
        int minMissingDays;
        /** 这只的分钟线文件（lc5, lc1），可能为 null 或含 null */
        File[] minFiles;
        /** 补分钟线失败的原因（空 = 没失败） */
        String minFailNote = "";

        boolean clean() {
            return !dbBroken && dbExists && missing.isEmpty() && !needTable && !needDivTable
                    && !needMin;
        }
    }

    // ================================================================== 对账

    /**
     * 逐只对账，结论写进台账（完整的记「跳过」，有问题记「失败」）。
     *
     * @param minTime 只比这个日期（yyyyMMdd）之后的，0 = 全部历史
     */
    public static Summary scan(final java.util.function.Consumer<String> log, final ItemProgress items,
                               final AtomicBoolean cancel, final Collection<String> onlyCodes,
                               final long minTime) throws Exception {
        return scan(log, items, cancel, onlyCodes, minTime, true);
    }

    /**
     * 带开关的对账：{@code checkMin5=false} 时只核日线，5 分钟线整项跳过。
     */
    public static Summary scan(final java.util.function.Consumer<String> log, final ItemProgress items,
                               final AtomicBoolean cancel, final Collection<String> onlyCodes,
                               final long minTime, final boolean checkMin5) throws Exception {
        long start = System.currentTimeMillis();
        final Summary summary = new Summary();
        final File dbDir = requireDbDir();
        final File vipdoc = TdxDataDir.requireVipdoc();
        final List<Job> jobs = collectJobs(vipdoc, onlyCodes);
        final Map<String, File[]> minFiles = MinDataUpdater.minuteFiles(vipdoc);

        report(log, "数据文件夹: " + dbDir.getAbsolutePath());
        report(log, "本地数据来源目录: " + vipdoc.getAbsolutePath());
        report(log, "待对账 " + jobs.size() + " 只"
                + (minTime > 0 ? "（只看 " + fmt(minTime) + " 之后的日线）" : "（全部历史）"));
        report(log, "对账范围: 日线逐根核；5 分钟线"
                + (checkMin5
                        ? "逐只核「有没有表 / 末根有没有落后 + 近 " + RECENT_DAYS
                                + " 个交易日有没有整天断」（有本地分钟文件的 "
                                + minFiles.size() + " 只，其余可以走在线线路补）"
                        : "本次不检查"));

        items.planned(keysOf(jobs));
        final int workers = Math.max(1, Math.min(Parallelism.resolve(), jobs.size()));
        report(log, "开始对账，" + Parallelism.describe(workers));

        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        final AtomicInteger missDb = new AtomicInteger();
        final AtomicInteger broken = new AtomicInteger();
        final AtomicInteger gap = new AtomicInteger();
        final AtomicInteger minBehind = new AtomicInteger();
        /** 5 分钟线核对了多少只（有日线库就算，不再限定必须有本地分钟文件） */
        final AtomicInteger minChecked = new AtomicInteger();
        final AtomicInteger minNeedOnline = new AtomicInteger();
        final AtomicLong missingBars = new AtomicLong();
        final AtomicInteger done = new AtomicInteger();

        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        if (cancel != null && cancel.get()) {
                            return;
                        }
                        int index = cursor.getAndIncrement();
                        if (index >= jobs.size()) {
                            return;
                        }
                        Job job = jobs.get(index);
                        items.begin(job.code);
                        try {
                            Inspection in = inspect(job, dbDir, minTime, minFiles, checkMin5);
                            if (in.minChecked) {
                                minChecked.incrementAndGet();
                            }
                            if (in.needMin) {
                                minBehind.incrementAndGet();
                            }
                            if (in.minNeedOnline) {
                                minNeedOnline.incrementAndGet();
                            }
                            if (in.dbBroken) {
                                broken.incrementAndGet();
                                items.fail(job.code, "库文件打不开：" + in.brokenReason);
                            } else if (!in.dbExists) {
                                missDb.incrementAndGet();
                                missingBars.addAndGet(in.tdxBars);
                                items.fail(job.code, "本地没有库（数据来源有 " + in.tdxBars + " 根）");
                            } else if (in.clean()) {
                                okCount.incrementAndGet();
                                items.done(job.code, "完整 · " + in.localBars + " 根");
                            } else {
                                gap.incrementAndGet();
                                missingBars.addAndGet(in.missing.size());
                                items.fail(job.code, describeProblem(in));
                            }
                        } catch (Throwable t) {
                            // 单只读坏了不能让整轮对账停住（文件被占用、盘掉线、库损坏）
                            broken.incrementAndGet();
                            items.fail(job.code, "检查失败：" + oneLine(t));
                        } finally {
                            int now = done.incrementAndGet();
                            if (now % 500 == 0) {
                                report(log, "已对账 " + now + "/" + jobs.size());
                            }
                        }
                    }
                }
            }, "integrity-" + (i + 1));
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 用户点了「重试」/「续跑」但本地数据来源里找不到这几只：也要给个明确的结论，
        // 否则它们会永远停在「未开始」，下次还会被当成没做完。
        if (onlyCodes != null) {
            Set<String> planned = new HashSet<String>(keysOf(jobs));
            for (String code : onlyCodes) {
                if (!planned.contains(code)) {
                    items.fail(code, "本地数据来源目录里找不到这只股票的日线文件");
                }
            }
        }

        summary.total = jobs.size();
        summary.ok = okCount.get();
        summary.missingDb = missDb.get();
        summary.broken = broken.get();
        summary.gap = gap.get();
        summary.missingBars = missingBars.get();
        summary.minBehind = minBehind.get();
        summary.minChecked = minChecked.get();
        summary.minNeedOnline = minNeedOnline.get();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeScan());
        reportMinuteScan(log, checkMin5, minChecked.get(), minBehind.get(), minNeedOnline.get());
        return summary;
    }

    /**
     * 5 分钟线单独给一句结论。
     *
     * <p>只写在开头那句「对账范围」里太容易被后面几百行进度冲掉，用户会以为根本没查分钟线。</p>
     */
    private static void reportMinuteScan(java.util.function.Consumer<String> log,
                                         boolean checkMin5, int checked, int behind,
                                         int needOnline) {
        if (!checkMin5) {
            report(log, "5 分钟线：本次没有检查（在参数区把「检查 5 分钟数据」勾上再跑一次）");
            return;
        }
        if (checked == 0) {
            report(log, "5 分钟线：没有可核对的股票库，先把日线补上再来");
            return;
        }
        report(log, "5 分钟线：核对 " + checked + " 只，"
                + (behind == 0 ? "全部到最新" : "落后 / 缺数据 " + behind + " 只，点「修复缺失」会补到最新")
                + (needOnline > 0 ? "；其中 " + needOnline + " 只没有本地分钟文件，修复时走在线线路" : ""));
    }

    /** 修复完给一句 5 分钟线的结论：处理了几只、补进去多少根 */
    private static void reportMinuteRepair(java.util.function.Consumer<String> log,
                                           int checked, long rows, int onlineCodes,
                                           long onlineRows) {
        if (checked == 0 && onlineCodes == 0) {
            report(log, "5 分钟线：这次没有需要补的股票");
            return;
        }
        report(log, "5 分钟线：本地分钟文件处理 " + checked + " 只、补入 " + rows + " 根"
                + "；在线线路补 " + onlineCodes + " 只、补入 " + onlineRows + " 根");
    }

    // ================================================================== 修复

    /**
     * 按台账里那几只有问题的股票补数据。
     *
     * <p>每只重新对账一次再补（不依赖上一轮扫描的结果）：这样即便用户是隔天
     * 从历史台账点「修复缺失」，补的也是「现在真正缺的」那几根。</p>
     */
    public static Summary repair(final java.util.function.Consumer<String> log, final ItemProgress items,
                                 final AtomicBoolean cancel, final Collection<String> onlyCodes,
                                 final long minTime) throws Exception {
        return repair(log, items, cancel, onlyCodes, minTime, true, true);
    }

    /**
     * 带开关的修复：{@code checkMin5=false} 只补日线；{@code onlineMin5=true} 时，
     * 本地数据来源没有分钟文件的那几只改走在线线路补 5 分钟线。
     */
    public static Summary repair(final java.util.function.Consumer<String> log, final ItemProgress items,
                                 final AtomicBoolean cancel, final Collection<String> onlyCodes,
                                 final long minTime, final boolean checkMin5,
                                 final boolean onlineMin5) throws Exception {
        long start = System.currentTimeMillis();
        final Summary summary = new Summary();
        final File dbDir = requireDbDir();
        final File vipdoc = TdxDataDir.requireVipdoc();
        final List<Job> jobs = collectJobs(vipdoc, onlyCodes);
        final Map<String, File[]> minFiles = MinDataUpdater.minuteFiles(vipdoc);

        report(log, "开始修复 " + jobs.size() + " 只"
                + (minTime > 0 ? "（只补 " + fmt(minTime) + " 之后缺的日线）" : "（补全部缺失）"));
        report(log, checkMin5
                ? "缺日线就补那几根；缺 5 分钟线的，有本地分钟文件就从文件补到最新，"
                        + "没有文件的" + (onlineMin5 ? "再走在线线路补齐" : "本次跳过")
                : "只补缺的日线，本次不检查 5 分钟线");
        items.planned(keysOf(jobs));

        final int workers = Math.max(1, Math.min(Parallelism.resolve(), jobs.size()));
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger okCount = new AtomicInteger();
        final AtomicLong rows = new AtomicLong();
        final AtomicLong minRows = new AtomicLong();
        final AtomicInteger minChecked = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        /** 本地没有分钟文件、需要在线补的代码，全部日线修完后统一跑一趟 */
        final List<String> onlineCodes = Collections.synchronizedList(new ArrayList<String>());

        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        if (cancel != null && cancel.get()) {
                            return;
                        }
                        int index = cursor.getAndIncrement();
                        if (index >= jobs.size()) {
                            return;
                        }
                        Job job = jobs.get(index);
                        items.begin(job.code);
                        try {
                            RepairResult result = repairOne(job, dbDir, minTime, minFiles,
                                    checkMin5);
                            if (result.rows > 0) {
                                rows.addAndGet(result.rows);
                            }
                            if (result.hadMinFiles) {
                                minChecked.incrementAndGet();
                            }
                            if (result.minRows > 0) {
                                minRows.addAndGet(result.minRows);
                            }
                            if (result.needOnlineMin) {
                                onlineCodes.add(job.code);
                            }
                            okCount.incrementAndGet();
                            if (result.rows == 0 && result.minRows == 0) {
                                items.skip(job.code, result.note);
                            } else {
                                items.done(job.code, result.note);
                            }
                        } catch (Throwable t) {
                            items.fail(job.code, "修复失败：" + oneLine(t));
                            Log.error("查缺补漏修复失败 " + job.code + ": " + t);
                        } finally {
                            int now = done.incrementAndGet();
                            if (now % 200 == 0) {
                                report(log, "已修复 " + now + "/" + jobs.size()
                                        + "，累计补入 " + rows.get() + " 根日线、"
                                        + minRows.get() + " 根 5 分钟线");
                            }
                        }
                    }
                }
            }, "repair-" + (i + 1));
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 本地分钟文件补不了的那些，统一走在线线路跑一趟，别在逐只修复里反复开线程池。
        // 在线那一档自己会在东方财富 / 新浪 / 腾讯之间逐只换源，一家抽风不会整批补不上。
        long onlineRows = 0;
        if (!onlineCodes.isEmpty() && onlineMin5) {
            report(log, "5 分钟线：有 " + onlineCodes.size()
                    + " 只没有本地分钟文件，改用在线线路逐只补齐（多家依次）…");
            try {
                int[] stat = Min5Online.run(log, cancel, onlineCodes);
                onlineRows = stat[1];
                report(log, "5 分钟线：在线补成功 " + stat[0] + " 只、写入 " + stat[1] + " 根"
                        + (stat[2] > 0 ? "，仍有 " + stat[2] + " 只没取到" : ""));
            } catch (Throwable t) {
                report(log, "5 分钟线：在线线路没补成（" + oneLine(t)
                        + "），日线不受影响；可以单独跑一次「分钟数据（5 分钟）」");
                Log.error("在线补 5 分钟线失败：" + oneLine(t));
            }
        } else if (!onlineCodes.isEmpty()) {
            report(log, "5 分钟线：有 " + onlineCodes.size()
                    + " 只没有本地分钟文件，本次按设置跳过了在线补齐");
        }

        summary.total = jobs.size();
        summary.ok = okCount.get();
        summary.rowsWritten = rows.get();
        summary.minRowsWritten = minRows.get() + onlineRows;
        summary.minOnlineRows = onlineRows;
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeRepair());
        reportMinuteRepair(log, minChecked.get(), minRows.get(),
                onlineMin5 ? onlineCodes.size() : 0, onlineRows);
        return summary;
    }

    private static final class RepairResult {
        int rows;
        int minRows;
        /** 这只在本地数据来源里有分钟文件（用来在日志里说清「5 分钟线处理了几只」） */
        boolean hadMinFiles;
        /** 没有本地分钟文件，5 分钟线转给在线线路补 */
        boolean needOnlineMin;
        String note = "";
    }

    /** 修一只：缺库就建、库坏了先备份再建、缺表就补表、缺日期就只补那几根、分钟线落后就补分钟线 */
    private static RepairResult repairOne(Job job, File dbDir, long minTime,
                                          Map<String, File[]> minFiles,
                                          boolean checkMin5) throws Exception {
        RepairResult result = new RepairResult();
        File dbFile = new File(dbDir, job.code + ".db");
        boolean existed = dbFile.isFile();

        Inspection in = inspect(job, dbDir, minTime, minFiles, checkMin5);
        if (in.data.isEmpty()) {
            // 分钟线依附日线库：没有日线就不该为它单独造一个只有分钟表的空壳库
            result.note = "本地数据来源里没有这只的日线";
            return result;
        }

        String backupNote = "";
        if (in.dbBroken) {
            // 坏库不能直接覆盖：里面可能还有用户自己算出来的东西。改名留着，让人能捞
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                    .format(new java.util.Date());
            File backup = new File(dbDir, job.code + ".db.broken-" + stamp);
            if (dbFile.renameTo(backup)) {
                backupNote = "，坏库已改名为 " + backup.getName();
                existed = false;
            } else {
                throw new IOException("库文件损坏且无法改名备份：" + dbFile.getAbsolutePath());
            }
        }

        Connection conn = null;
        try {
            conn = DBConnection.connect(job.code, true);
            DataTableManager table = new DataTableManager();
            table.setConn(conn);

            boolean fresh = !existed;
            if (fresh || in.needTable || in.needDivTable) {
                // CREATE TABLE IF NOT EXISTS：缺哪张补哪张，已经有的不动
                table.createDataTable(job.code);
            }

            List<TdxStockData> toWrite;
            if (fresh) {
                toWrite = in.data;
            } else if (in.missing.isEmpty()) {
                toWrite = Collections.emptyList();
            } else {
                Set<Long> missing = new HashSet<Long>(in.missing);
                toWrite = new ArrayList<TdxStockData>(in.missing.size());
                for (TdxStockData data : in.data) {
                    if (missing.contains(Long.valueOf(data.getDate()))) {
                        toWrite.add(data);
                    }
                }
            }

            if (!toWrite.isEmpty()) {
                conn.setAutoCommit(false);
                result.rows = table.insertBatch(toWrite);
                conn.commit();
            }

            // 日线补完再补 5 分钟：两者在同一个库里，分钟线只补不删（以前按 6 个月滚动，
            // 用户攒的老数据会被清掉），所以它不跟 minTime 走 —— 落后就补到最新那一刻为止
            result.hadMinFiles = in.minFiles != null;
            if (in.needMin) {
                if (in.minNeedOnline) {
                    // 本地数据来源没有这只的分钟文件：交给整轮修复末尾的在线线路统一补
                    result.needOnlineMin = true;
                } else {
                    result.minRows = repairMinutes(job, in, conn);
                }
            }
            result.note = repairNote(in, fresh, backupNote, result);
            return result;
        } catch (Throwable t) {
            rollbackQuietly(conn);
            throw t instanceof Exception ? (Exception) t : new RuntimeException(t);
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    /**
     * 补一只股票的 5 分钟线。
     *
     * <p>顺序很关键：{@code writeOne} 自己会开事务、也要关连接，所以先把日线那个连接
     * 放回自动提交，别让两条事务缠在一起。</p>
     *
     * @return 写入的 5 分钟根数；这只没有分钟文件时返回 0
     */
    private static int repairMinutes(Job job, Inspection in, Connection conn) {
        if (in.minFiles == null) {
            return 0;
        }
        try {
            conn.setAutoCommit(true);
            List<TdxMinBar> bars = TdxMinDataReader.readFiveMinute(in.minFiles[0], in.minFiles[1]);
            // fillGaps=true：查缺补漏要的不只是「末尾跟上」，中间断掉的某几天也得补回来
            return MinDataUpdater.writeBars(job.code, bars, true);
        } catch (Exception e) {
            // 分钟线补不上不该把已经补好的日线判成失败：日志里留一句，台账照常写
            Log.error("补 " + job.code + " 的 5 分钟线失败：" + e.getMessage());
            in.minFailNote = oneLine(e);
            return 0;
        }
    }

    /** 台账里那句话：补了什么就说什么，别让人从「完成」两个字里去猜 */
    private static String repairNote(Inspection in, boolean fresh, String backupNote,
                                     RepairResult result) {
        StringBuilder sb = new StringBuilder();
        if (fresh) {
            sb.append(in.dbBroken ? "重建库" : "新建库").append("，写入 ").append(result.rows).append(" 根");
        } else if (result.rows > 0) {
            sb.append("补入 ").append(result.rows).append(" 根");
            if (in.segments > 1) {
                sb.append("（").append(in.segments).append(" 段）");
            }
        }
        if (!fresh && (in.needTable || in.needDivTable)) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("补齐了缺的表");
        }
        if (result.minRows > 0) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("补入 5 分钟线 ").append(result.minRows).append(" 根");
        } else if (in.needMin) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            if (result.needOnlineMin) {
                sb.append("5 分钟线缺数据，转在线线路补");
            } else {
                sb.append(in.minFailNote.isEmpty()
                        ? "5 分钟线没有可补的新数据" : "5 分钟线补失败：" + in.minFailNote);
            }
        }
        if (sb.length() == 0) {
            sb.append("已经完整 · ").append(in.localBars).append(" 根");
        }
        sb.append(backupNote);
        return sb.toString();
    }

    // ================================================================== 单只对账

    private static Inspection inspect(Job job, File dbDir, long minTime,
                                      Map<String, File[]> minFiles, boolean checkMin5) {
        Inspection in = new Inspection();
        in.code = job.code;
        in.dbFile = new File(dbDir, job.code + ".db");
        in.dbExists = in.dbFile.isFile();
        in.minFiles = minFiles == null ? null : minFiles.get(job.code);

        List<TdxStockData> all;
        try {
            all = TdxDayDataReader.readAllDay(job.file.getPath());
        } catch (IOException e) {
            throw new IllegalStateException("读本地数据来源的日线失败：" + e.getMessage(), e);
        }
        if (all.isEmpty()) {
            in.data = Collections.emptyList();
            in.tdxBars = 0;
            return in;
        }

        List<TdxStockData> data;
        if (minTime > 0) {
            data = new ArrayList<TdxStockData>(all.size());
            for (TdxStockData one : all) {
                if (one.getDate() >= minTime) {
                    data.add(one);
                }
            }
        } else {
            data = all;
        }
        in.data = data;
        in.tdxBars = data.size();
        if (!data.isEmpty()) {
            in.firstTdx = data.get(0).getDate();
            in.lastTdx = data.get(data.size() - 1).getDate();
        }

        if (!in.dbExists) {
            // 连库都没有，5 分钟线必然也是空的：记成待补，修复时随建库一起补回来。
            // 有没有本地分钟文件决定走文件还是走在线线路。
            if (checkMin5) {
                in.minChecked = true;
                in.needMin = true;
                in.minNeedOnline = in.minFiles == null
                        || (in.minFiles[0] == null && in.minFiles[1] == null);
            }
            return in;
        }

        Connection conn = null;
        try {
            conn = DBConnection.connect(job.code, false);
            if (!hasTable(conn, "T_D_Day")) {
                in.needTable = true;
            }
            in.needDivTable = !hasTable(conn, DivdendTableManager.tableName());

            Set<Long> local = loadTimes(conn, minTime);
            in.localBars = local.size();
            if (in.localBars > 0) {
                long last = 0;
                for (Long time : local) {
                    if (time.longValue() > last) {
                        last = time.longValue();
                    }
                }
                in.lastLocal = last;
            }
            inspectMinutes(in, conn, checkMin5);
            if (in.data.isEmpty()) {
                return in;
            }

            // 以本地数据来源为准逐个核：本地没有的日期就是缺
            boolean inGap = false;
            int tail = 0;
            for (int i = 0; i < data.size(); i++) {
                long date = data.get(i).getDate();
                boolean present = local.contains(Long.valueOf(date));
                if (present) {
                    if (inGap) {
                        inGap = false;
                    }
                    tail = 0;
                } else {
                    in.missing.add(Long.valueOf(date));
                    if (!inGap) {
                        inGap = true;
                        in.segments++;
                    }
                    tail++;
                }
            }
            in.tailMissing = tail;
            return in;
        } catch (Throwable t) {
            in.dbBroken = true;
            in.brokenReason = oneLine(t);
            in.missing.clear();
            in.segments = 0;
            in.tailMissing = 0;
            return in;
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    /**
     * 5 分钟线判「有没有落后」：先看末根时间，再看最近几个交易日有没有整天断掉。
     *
     * <p><b>有日线库就核，不再要求本地有分钟文件</b>：以前没分钟文件的股票（基金、新股、
     * 没做过分钟下载）整项跳过，库里 {@code T_D_Min5} 空着也查不出来，更补不了。
     * 现在参照日改成「本地分钟文件末根」，没有文件就用日线最近交易日；查出来缺的
     * 由修复阶段走在线线路补齐。</p>
     *
     * <p>本地数据来源分钟文件只滚动 6 个月，逐根比既慢又没意义；真正会坏的是三种：
     * 表没建过、停在几天前、中间断了某几天。第三种拿「本地日线的最近十天」跟库里
     * 分钟线的日子对一下即可（停牌日本地日线里本来就没有，不会误报）。</p>
     *
     * <p><b>参照日早于 6 个月就不判</b>：退市、长期停牌的股票本来就没有新数据 ——
     * 判它「缺」会把台账刷成永远修不好的红条。</p>
     */
    private static void inspectMinutes(Inspection in, Connection conn, boolean checkMin5) {
        if (!checkMin5) {
            return;
        }
        in.minChecked = true;
        boolean hasLocalMin = in.minFiles != null
                && (in.minFiles[0] != null || in.minFiles[1] != null);
        long cutoff = MinDataUpdater.monthsAgoCutoff(MinDataUpdater.STALE_MONTHS);
        long cutoffDate = cutoff / 10000L;
        long refDate;
        if (hasLocalMin) {
            in.tdxLastMin = TdxMinDataReader.lastFiveMinuteTime(in.minFiles[0], in.minFiles[1]);
            refDate = in.tdxLastMin / 10000L;
        } else {
            in.minNeedOnline = true;
            refDate = Math.max(in.lastLocal, in.lastTdx);
        }
        if (refDate <= 0 || refDate < cutoffDate) {
            in.minChecked = false;
            in.minNeedOnline = false;
            return;
        }

        in.localLastMin = MinDataUpdater.lastTime(conn);
        if (in.localLastMin <= 0 || in.localLastMin < cutoff) {
            // 没建过表 / 表是空的 / 只剩保留期之前的老数据：整段都要补
            in.needMin = true;
            in.minMissingDays = RECENT_DAYS;
            return;
        }
        if (hasLocalMin && in.localLastMin < in.tdxLastMin) {
            in.needMin = true;
        }
        in.minMissingDays = countMissingMinDays(conn, refDate);
        if (in.minMissingDays > 0) {
            in.needMin = true;
        }
        if (!in.needMin) {
            in.minNeedOnline = false;
        }
    }

    /**
     * 最近 {@value #RECENT_DAYS} 个交易日里，有几天本地分钟线一根都没有。
     *
     * @param lastMinDate 本地数据来源分钟线的最后一根所属日期（yyyyMMdd），比它晚的日子不参与
     */
    private static int countMissingMinDays(Connection conn, long lastMinDate) {
        List<Long> days = recentDayDates(conn, lastMinDate);
        if (days.isEmpty()) {
            return 0;
        }
        Set<Long> have = minuteDays(conn, days.get(0) * 10000L);
        int missing = 0;
        for (Long day : days) {
            if (!have.contains(day)) {
                missing++;
            }
        }
        return missing;
    }

    /** 本地日线里不晚于 {@code lastDate} 的最近 {@value #RECENT_DAYS} 个交易日（升序） */
    private static List<Long> recentDayDates(Connection conn, long lastDate) {
        List<Long> days = new ArrayList<Long>(RECENT_DAYS);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TIME FROM T_D_Day WHERE TIME <= ? ORDER BY TIME DESC LIMIT ?")) {
            ps.setLong(1, lastDate);
            ps.setInt(2, RECENT_DAYS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(Long.valueOf(rs.getLong(1)));
                }
            }
        } catch (SQLException e) {
            return Collections.emptyList();
        }
        Collections.reverse(days);
        return days;
    }

    /** 库里 5 分钟线覆盖到的日子（yyyyMMdd），从 {@code fromTime} 起算 */
    private static Set<Long> minuteDays(Connection conn, long fromTime) {
        Set<Long> days = new HashSet<Long>(64);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT TIME / 10000 FROM T_D_Min5 WHERE TIME >= ?")) {
            ps.setLong(1, fromTime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(Long.valueOf(rs.getLong(1)));
                }
            }
        } catch (SQLException e) {
            return days;
        }
        return days;
    }

    /** 「缺 12 根（3 段），最早缺 20230612，末位 20241101（数据来源到 20241108）」 */
    private static String describeProblem(Inspection in) {
        StringBuilder sb = new StringBuilder();
        if (!in.missing.isEmpty()) {
            if (in.localBars == 0) {
                // 空壳库（0 字节 / 表被清空）：直接说清楚，别让人以为是「缺了几十根」
                sb.append("本地库是空的（数据来源有 ").append(in.tdxBars).append(" 根）");
            } else if (in.tailMissing == in.missing.size()) {
                // 全缺在末尾 = 这只停在旧日期上，是现实里最常见的一种
                sb.append("末尾缺 ").append(in.missing.size()).append(" 根（本地停在 ")
                        .append(fmt(in.lastLocal)).append("，数据来源到 ")
                        .append(fmt(in.lastTdx)).append("）");
            } else {
                sb.append("缺 ").append(in.missing.size()).append(" 根日线");
                if (in.segments > 1) {
                    sb.append("（").append(in.segments).append(" 段）");
                }
                sb.append("，最早缺 ").append(fmt(in.missing.get(0).longValue()));
                if (in.tailMissing > 0) {
                    sb.append("，本地末 ").append(fmt(in.lastLocal))
                            .append("（数据来源到 ").append(fmt(in.lastTdx)).append("）");
                }
            }
        }
        if (in.needTable) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("缺日线表");
        }
        if (in.needDivTable) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("缺除权表");
        }
        if (in.needMin) {
            StringBuilder min = new StringBuilder();
            if (in.minNeedOnline && in.tdxLastMin <= 0) {
                min.append("没有 5 分钟线（本地无分钟文件，可走在线线路补）");
            } else if (in.localLastMin <= 0) {
                min.append("没有 5 分钟线（数据来源到 ").append(minuteText(in.tdxLastMin)).append("）");
            } else if (in.localLastMin < in.tdxLastMin) {
                min.append("5 分钟线停在 ").append(minuteText(in.localLastMin))
                        .append("（数据来源到 ").append(minuteText(in.tdxLastMin)).append("）");
            }
            if (in.minMissingDays > 0) {
                if (min.length() > 0) {
                    min.append("，");
                }
                min.append("近 ").append(RECENT_DAYS).append(" 个交易日缺 ")
                        .append(in.minMissingDays).append(" 天");
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(min);
        }
        return sb.length() == 0 ? "有缺失" : sb.toString();
    }

    /** 202611101005 → 2026-11-10 10:05，台账里那一列窄，日期必须带分隔才看得懂 */
    private static String minuteText(long time) {
        if (time <= 0) {
            return "—";
        }
        String text = Long.toString(time);
        if (text.length() < 12) {
            return text;
        }
        return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8)
                + " " + text.substring(8, 10) + ":" + text.substring(10, 12);
    }

    // ================================================================== 工具

    private static File requireDbDir() {
        String dir = MountConfig.currentDbDir();
        if (dir == null || dir.trim().isEmpty()) {
            throw new IllegalStateException(NO_DATA_DIR);
        }
        File root = new File(dir);
        if (!root.isDirectory()) {
            throw new IllegalStateException("股票数据文件夹不存在：" + root.getAbsolutePath());
        }
        return root;
    }

    /**
     * 把本地数据来源 sh/sz 目录里的个股日线文件排成队。
     *
     * @param onlyCodes 非空时只要这些（重试 / 续跑 / 修复）
     */
    private static List<Job> collectJobs(File vipdoc, Collection<String> onlyCodes) throws Exception {
        Set<String> wanted = onlyCodes == null ? null : new LinkedHashSet<String>(onlyCodes);
        List<Job> jobs = new ArrayList<Job>();
        for (String prefix : new String[]{"sh", "sz"}) {
            File dir = new File(new File(vipdoc, prefix), "lday");
            File[] files = FileTool.listFilesBasic(dir.getPath());
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String code = ReaderFromTdx.codeOf(prefix, file.getName());
                if (code == null) {
                    continue;
                }
                if (wanted != null && !wanted.contains(code)) {
                    continue;
                }
                jobs.add(new Job(code, file));
            }
        }
        Collections.sort(jobs, new Comparator<Job>() {
            @Override
            public int compare(Job a, Job b) {
                return a.code.compareTo(b.code);
            }
        });
        return jobs;
    }

    private static List<String> keysOf(List<Job> jobs) {
        List<String> keys = new ArrayList<String>(jobs.size());
        for (Job job : jobs) {
            keys.add(job.code);
        }
        return keys;
    }

    private static boolean hasTable(Connection conn, String name) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?");
            ps.setString(1, name);
            rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    private static Set<Long> loadTimes(Connection conn, long minTime) throws SQLException {
        Set<Long> times = new HashSet<Long>(4096);
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery(minTime > 0
                    ? "SELECT TIME FROM T_D_Day WHERE TIME >= " + minTime
                    : "SELECT TIME FROM T_D_Day");
            while (rs.next()) {
                long value = rs.getLong(1);
                if (value > 0) {
                    times.add(Long.valueOf(value));
                }
            }
            return times;
        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 读检查用的资源，关不掉不影响结论
            }
        }
    }

    private static void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 已经断开或从未开事务
            }
        }
    }

    private static void report(java.util.function.Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static String fmt(long date) {
        return date <= 0 ? "—" : Long.toString(date);
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        }
        return (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分";
    }

    /** 异常压成一行：台账里一行一只，不能塞堆栈 */
    private static String oneLine(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
