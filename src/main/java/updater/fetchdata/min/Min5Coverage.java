package updater.fetchdata.min;

import updater.db.DBConnection;
import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.min.MinDataUpdater;
import updater.fetchdata.tdx.min.TdxMinDataReader;
import updater.mount.MountConfig;
import updater.util.Parallelism;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 5 分钟线「到哪一天了」：一边是本地库（{@code T_D_Min5}），一边是本地分钟文件。
 *
 * <p>用户打开「分钟数据（5 分钟）」先要知道两件事：库里最深的 K 线是哪一天、
 * 本地数据来源里的分钟文件最深到哪一天 —— 两个数一样就不用跑任何任务，
 * 库里浅一截就说明该补了。以前这两个数只能从日志里翻，跑完一趟才知道。</p>
 *
 * <p><b>为什么扫全量</b>：库是「一只股票一个文件」，没有汇总表，最大时刻只能逐个
 * {@code SELECT MAX(TIME)}（TIME 是主键，走索引，单只毫秒级）。本地文件那份只读每个
 * 文件的最后一根记录（{@link TdxMinDataReader#lastFiveMinuteTime}），不解析整个文件。
 * 5000 多只两样加起来通常几秒，跑在后台线程里，界面不会卡。</p>
 *
 * <p>只读：不建表、不写库、不改文件。</p>
 */
public final class Min5Coverage {

    /** 库里最深的 5 分钟时刻（yyyyMMddHHmm）；0 = 一个库都没有分钟线 */
    public long dbMaxTime;
    /** 有 T_D_Min5 且非空的库数 */
    public int dbStocks;
    /** 本地分钟文件最深的时刻；0 = 没有分钟文件 */
    public long localMaxTime;
    /** 有分钟文件的股票数 */
    public int localStocks;
    public long elapsedMs;
    /** 没挂载数据文件夹时给用户的原话（空 = 正常） */
    public String dbNote = "";
    /** 没配本地数据来源时给用户的原话（空 = 正常） */
    public String localNote = "";

    /** 库里比本地文件浅多少个自然日（本地更浅或两边都没有则返回 0） */
    public int behindDays() {
        if (dbMaxTime <= 0 || localMaxTime <= 0 || dbMaxTime >= localMaxTime) {
            return 0;
        }
        return (int) (dayOf(localMaxTime) - dayOf(dbMaxTime));
    }

    private static long dayOf(long time) {
        // yyyyMMddHHmm → 粗算天数差用不着日历精度：只要同一天返回 0、差一天返回 1
        java.time.LocalDate date = toDate(time);
        return date == null ? 0 : date.toEpochDay();
    }

    /** yyyyMMddHHmm → LocalDate，越界返回 null */
    public static java.time.LocalDate toDate(long time) {
        if (time <= 0) {
            return null;
        }
        long day = time / 10000L;
        try {
            return java.time.LocalDate.of((int) (day / 10000L), (int) (day / 100 % 100),
                    (int) (day % 100));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 「2026-11-13 15:00」；0 显示成「—」 */
    public static String text(long time) {
        if (time <= 0) {
            return "—";
        }
        java.time.LocalDate date = toDate(time);
        if (date == null) {
            return "—";
        }
        long hhmm = time % 10000L;
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                hhmm / 100, hhmm % 100);
    }

    /**
     * 扫一遍两边。
     *
     * @param cancel 停止标志，可为 null（统计很快就完，一般不会用到）
     */
    public static Min5Coverage scan(AtomicBoolean cancel) {
        long start = System.currentTimeMillis();
        Min5Coverage result = new Min5Coverage();
        result.scanDb(cancel);
        result.scanLocal(cancel);
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    // ------------------------------------------------------------------ 库

    private void scanDb(AtomicBoolean cancel) {
        String dirPath = MountConfig.currentDbDir();
        File dir = dirPath == null || dirPath.isEmpty() ? null : new File(dirPath);
        if (dir == null || !dir.isDirectory()) {
            dbNote = MountConfig.NO_STOCK_DATA_MESSAGE;
            return;
        }
        List<String> codes = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) {
            dbNote = "数据文件夹读不出来：" + dir.getAbsolutePath();
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".db")) {
                continue;
            }
            codes.add(name.substring(0, name.length() - 3));
        }
        if (codes.isEmpty()) {
            dbNote = "数据文件夹里还没有股票库";
            return;
        }

        final AtomicLong max = new AtomicLong();
        final AtomicInteger withData = new AtomicInteger();
        final AtomicInteger cursor = new AtomicInteger();
        final List<String> list = codes;
        // 这一趟是纯磁盘 I/O（开几千个库、各读一行），路数开太多反而互相抢磁道：
        // 32 核机器上 30 路比 8 路慢好几倍。上限 8 足够了。
        int workers = Math.max(1, Math.min(Math.min(Parallelism.resolve(), 8), list.size()));
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
                        if (index >= list.size()) {
                            return;
                        }
                        java.sql.Connection conn = null;
                        try {
                            conn = DBConnection.connect(list.get(index), false);
                            long last = MinDataUpdater.lastTime(conn);
                            if (last > 0) {
                                withData.incrementAndGet();
                                max.accumulateAndGet(last, Math::max);
                            }
                        } catch (Throwable ignored) {
                            // 单只库打不开（坏库 / 加密文件）不影响「最深到哪一天」这个结论
                        } finally {
                            DBConnection.closeQuietly(conn);
                        }
                    }
                }
            }, "min5-coverage-" + (i + 1));
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
        dbMaxTime = max.get();
        dbStocks = withData.get();
        if (dbStocks == 0) {
            dbNote = "所有股票库都还没有 5 分钟线";
        }
    }

    // ------------------------------------------------------------------ 本地文件

    private void scanLocal(AtomicBoolean cancel) {
        String path = TdxDataDir.configuredPath();
        if (path.isEmpty()) {
            localNote = "还没设置本地数据来源";
            return;
        }
        File vipdoc = new File(new File(path), "vipdoc");
        if (!vipdoc.isDirectory()) {
            localNote = "本地数据来源里没有 vipdoc 目录";
            return;
        }
        Map<String, File[]> files = MinDataUpdater.minuteFiles(vipdoc);
        if (files.isEmpty()) {
            localNote = "本地没有分钟线文件（行情软件还没下载分钟数据）";
            return;
        }
        long max = 0;
        int count = 0;
        for (Map.Entry<String, File[]> entry : files.entrySet()) {
            if (cancel != null && cancel.get()) {
                return;
            }
            File[] pair = entry.getValue();
            long last = TdxMinDataReader.lastFiveMinuteTime(pair[0], pair[1]);
            if (last > 0) {
                count++;
                if (last > max) {
                    max = last;
                }
            }
        }
        localMaxTime = max;
        localStocks = count;
    }
}
