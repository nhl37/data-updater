package updater.main.updater;

import updater.db.DBConnection;
import updater.db.DivdendTableManager;
import updater.fetchdata.DividendData;
import updater.fetchdata.DivdendDataWriter;
import updater.fetchdata.tdx.xdxr.TdxLocalXdxrReader;
import updater.log.Log;
import updater.mount.MountConfig;
import updater.util.ItemProgress;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 除权的查缺补漏：拿本地除权文件当参照，逐只核库里的 {@code T_Divdend}。
 *
 * <p><b>为什么要单独核对</b>：除权表是「前复权因子」的唯一来源，它缺了不会报错 ——
 * 选股脚本照常跑，只是复权价悄悄算错。现实里除权表出问题有四种：
 * 整个库没有这张表（老库 / 手建的库）、表被清空、新增的除权事件没进来（停在上一期）、
 * 库重建过而除权没跟着补。这四种都在这里查得出来，且都能靠「用本地文件整只重写」修好。</p>
 *
 * <p><b>参照是什么</b>：本地行情软件下发的 gbbq（股本变迁文件），由 python 解析成
 * 「代码 → 除权记录」（{@link TdxLocalXdxrReader}）。参照取不到就直接报错，不会拿
 * 「表在不在」这种弱条件糊弄 —— 那只会在除权表大面积缺失时给出一片绿。</p>
 *
 * <p><b>只比库内日线区间内的除权日</b>：除权日早于库里第一根日线的记录写不进去
 * （取不到除权日前收盘价），晚于最后一根日线的还没发生，两者都不该判成缺。</p>
 */
public final class DividendIntegrityChecker {

    private static final String NO_DATA_DIR = "还没有挂载股票数据文件夹";

    /** 一次对账 / 修复的汇总 */
    public static final class Summary {
        public int total;
        /** 与本地文件一致，不用动 */
        public int ok;
        /** 本地除权文件里这只没有记录，跳过 */
        public int noRef;
        /** 库里日线区间内没有除权日，跳过 */
        public int noEvent;
        /** 没有库文件 / 库打不开 */
        public int noDb;
        /** 缺 T_Divdend 表 */
        public int missingTable;
        /** 表在，但缺除权记录（含整表为空） */
        public int behind;
        /** 只在修复时有意义：重写成功的只数 */
        public int rewritten;
        public long elapsedMs;

        public String describeScan() {
            int bad = noDb + missingTable + behind;
            StringBuilder sb = new StringBuilder();
            sb.append("核对 ").append(total).append(" 只：一致 ").append(ok);
            if (bad == 0) {
                sb.append("，除权表没有发现缺失");
            } else {
                sb.append("，待补 ").append(bad).append(" 只（缺表 ").append(missingTable)
                        .append("、缺记录 ").append(behind).append("）");
            }
            if (noRef + noEvent > 0) {
                sb.append("；另有 ").append(noRef + noEvent).append(" 只没有可比对的除权事件");
            }
            sb.append(" · 用时 ").append(formatDuration(elapsedMs));
            if (bad > 0) {
                sb.append("（点「修复缺失」按台账重写）");
            }
            return sb.toString();
        }

        public String describeRepair() {
            return "修复完成：重写 " + rewritten + " 只的除权表 · 用时 " + formatDuration(elapsedMs);
        }
    }

    private DividendIntegrityChecker() {
    }

    // ================================================================== 对账

    public static Summary scan(final java.util.function.Consumer<String> log, final ItemProgress items,
                               final AtomicBoolean cancel, final Collection<String> onlyCodes)
            throws Exception {
        return run(log, items, cancel, onlyCodes, false);
    }

    /** 按台账里那几只重建除权表：用本地文件整只重写，旧表留成 T_Divdendbakup */
    public static Summary repair(final java.util.function.Consumer<String> log, final ItemProgress items,
                                 final AtomicBoolean cancel, final Collection<String> onlyCodes)
            throws Exception {
        return run(log, items, cancel, onlyCodes, true);
    }

    private static Summary run(final java.util.function.Consumer<String> log, final ItemProgress items,
                               final AtomicBoolean cancel, final Collection<String> onlyCodes,
                               boolean fix) throws Exception {
        long start = System.currentTimeMillis();
        final Summary summary = new Summary();

        report(log, "读取本地除权文件（gbbq）…");
        final Map<String, List<DividendData>> ref = TdxLocalXdxrReader.readFromLocalTdx();
        report(log, "本地除权文件里涉及 " + ref.size() + " 只股票");

        final File dbDir = requireDbDir();
        List<String> codes = listCodes(dbDir, onlyCodes);
        report(log, "数据文件夹: " + dbDir.getAbsolutePath());
        report(log, (fix ? "开始重写 " : "开始核对 ") + codes.size() + " 只的除权表");
        if (codes.isEmpty()) {
            summary.elapsedMs = System.currentTimeMillis() - start;
            report(log, "数据文件夹里没有可核对的股票库");
            return summary;
        }

        items.planned(codes);
        final int workers = Math.max(1, Math.min(updater.util.Parallelism.resolve(), codes.size()));
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger noRef = new AtomicInteger();
        final AtomicInteger noEvent = new AtomicInteger();
        final AtomicInteger noDb = new AtomicInteger();
        final AtomicInteger missTable = new AtomicInteger();
        final AtomicInteger behind = new AtomicInteger();
        final AtomicInteger rewritten = new AtomicInteger();
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
                        if (index >= codes.size()) {
                            return;
                        }
                        String code = codes.get(index);
                        items.begin(code);
                        try {
                            List<DividendData> list = ref.get(code);
                            if (list == null || list.isEmpty()) {
                                noRef.incrementAndGet();
                                items.skip(code, "本地除权文件里没有这只的记录");
                            } else if (!new File(dbDir, code + ".db").isFile()) {
                                noDb.incrementAndGet();
                                items.skip(code, "本地没有库文件");
                            } else {
                                Result result = fix
                                        ? repairOne(code, list, items)
                                        : checkOne(code, list, items);
                                switch (result.kind) {
                                    case OK:
                                        ok.incrementAndGet();
                                        break;
                                    case NO_EVENT:
                                        noEvent.incrementAndGet();
                                        break;
                                    case MISSING_TABLE:
                                        missTable.incrementAndGet();
                                        break;
                                    case BEHIND:
                                        behind.incrementAndGet();
                                        break;
                                    default:
                                        noDb.incrementAndGet();
                                        break;
                                }
                                if (result.rewritten) {
                                    rewritten.incrementAndGet();
                                }
                            }
                        } catch (Throwable t) {
                            noDb.incrementAndGet();
                            String message = oneLine(t);
                            items.fail(code, (fix ? "重写失败：" : "核对失败：") + message);
                            Log.error("除权" + (fix ? "重写" : "核对") + "失败 " + code + ": " + message);
                        } finally {
                            int now = done.incrementAndGet();
                            if (now % 500 == 0) {
                                report(log, (fix ? "已重写 " : "已核对 ") + now + "/" + codes.size());
                            }
                        }
                    }
                }
            }, "dividend-integrity-" + (i + 1));
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

        summary.total = codes.size();
        summary.ok = ok.get();
        summary.noRef = noRef.get();
        summary.noEvent = noEvent.get();
        summary.noDb = noDb.get();
        summary.missingTable = missTable.get();
        summary.behind = behind.get();
        summary.rewritten = rewritten.get();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, fix ? summary.describeRepair() : summary.describeScan());
        return summary;
    }

    // ================================================================== 单只

    private enum Kind {OK, NO_EVENT, MISSING_TABLE, BEHIND, NO_DB}

    private static final class Result {
        Kind kind = Kind.OK;
        boolean rewritten;
    }

    /** 核一只：表在不在、区间内的除权日是不是都在表里 */
    private static Result checkOne(String code, List<DividendData> ref, ItemProgress items) {
        Result result = new Result();
        Connection conn = null;
        try {
            conn = DBConnection.connect(code, false);
            long firstDay = minDay(conn);
            long lastDay = maxDay(conn);
            if (firstDay <= 0 || lastDay <= 0) {
                result.kind = Kind.NO_EVENT;
                items.skip(code, "库里没有日线，没有可比对的除权区间");
                return result;
            }
            List<Integer> expected = expectedDates(ref, firstDay, lastDay);
            if (expected.isEmpty()) {
                result.kind = Kind.NO_EVENT;
                items.skip(code, "库内日线区间里没有除权事件");
                return result;
            }
            if (!hasTable(conn)) {
                result.kind = Kind.MISSING_TABLE;
                items.fail(code, "缺除权表（应有 " + expected.size() + " 条）");
                return result;
            }
            Set<Integer> have = dividendDates(conn);
            List<Integer> missing = new ArrayList<Integer>();
            for (Integer date : expected) {
                if (!have.contains(date)) {
                    missing.add(date);
                }
            }
            if (missing.isEmpty()) {
                items.done(code, "一致 · " + have.size() + " 条");
                return result;
            }
            result.kind = Kind.BEHIND;
            items.fail(code, "除权表缺 " + missing.size() + " 条"
                    + (have.isEmpty() ? "（整表为空）" : "") + "，最早缺 " + fmt(missing.get(0)));
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException(oneLine(t), t);
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    /** 重写一只：用本地文件的全部记录整只重建（写入器自己会备份旧表） */
    private static Result repairOne(String code, List<DividendData> ref, ItemProgress items)
            throws Exception {
        Result result = new Result();
        // 写入器内部会自己开连接 / 关连接，这里先把连接放掉，别让两条事务缠在一起
        DivdendDataWriter.insertForwardAdjusted(code, new ArrayList<DividendData>(ref));
        int rows = countDividendRows(code);
        result.rewritten = rows > 0;
        if (rows > 0) {
            items.done(code, "重写 " + rows + " 条");
        } else {
            // 写入器把异常吞在内部，只能靠复查行数判断：0 条多半是库里日线与除权日对不上
            items.fail(code, "重写完仍是 0 条：库里日线覆盖不到这些除权日");
        }
        return result;
    }

    // ================================================================== 库内查询

    private static long minDay(Connection conn) {
        return oneValue(conn, "SELECT IFNULL(MIN(TIME),0) FROM T_D_Day");
    }

    private static long maxDay(Connection conn) {
        return oneValue(conn, "SELECT IFNULL(MAX(TIME),0) FROM T_D_Day");
    }

    private static long oneValue(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    private static boolean hasTable(Connection conn) throws SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, DivdendTableManager.tableName());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<Integer> dividendDates(Connection conn) {
        Set<Integer> dates = new HashSet<Integer>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TIME FROM " + DivdendTableManager.tableName())) {
            while (rs.next()) {
                dates.add(Integer.valueOf((int) rs.getLong(1)));
            }
        } catch (SQLException e) {
            return dates;
        }
        return dates;
    }

    private static int countDividendRows(String code) {
        Connection conn = null;
        try {
            conn = DBConnection.connect(code, false);
            return (int) oneValue(conn, "SELECT COUNT(*) FROM " + DivdendTableManager.tableName());
        } catch (Throwable t) {
            return 0;
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    /** 库内日线区间里的除权日（去重、升序） */
    private static List<Integer> expectedDates(List<DividendData> ref, long firstDay, long lastDay) {
        Set<Integer> set = new HashSet<Integer>();
        for (DividendData data : ref) {
            int date = data.exDividendDate;
            // 早于库里第一根日线的记录写不进去（取不到前收盘价），晚于最后一根的还没发生
            if (date > firstDay && date <= lastDay) {
                set.add(Integer.valueOf(date));
            }
        }
        List<Integer> list = new ArrayList<Integer>(set);
        Collections.sort(list);
        return list;
    }

    // ================================================================== 杂项

    private static File requireDbDir() {
        String dir = MountConfig.currentDbDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException(NO_DATA_DIR);
        }
        File file = new File(dir);
        if (!file.isDirectory()) {
            throw new IllegalStateException("数据文件夹不存在：" + file.getAbsolutePath());
        }
        return file;
    }

    private static List<String> listCodes(File dbDir, Collection<String> onlyCodes) {
        List<String> codes = new ArrayList<String>();
        File[] files = dbDir.listFiles();
        if (files == null) {
            return codes;
        }
        Set<String> allow = onlyCodes == null || onlyCodes.isEmpty()
                ? null : new HashSet<String>(onlyCodes);
        for (File file : files) {
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".db")) {
                continue;
            }
            String code = name.substring(0, name.length() - 3);
            if (code.trim().isEmpty()) {
                continue;
            }
            if (allow != null && !allow.contains(code)) {
                continue;
            }
            codes.add(code);
        }
        Collections.sort(codes);
        return codes;
    }

    private static String fmt(int date) {
        if (date <= 0) {
            return "—";
        }
        return date / 10000 + "-" + String.format(Locale.ROOT, "%02d-%02d",
                date / 100 % 100, date % 100);
    }

    private static String oneLine(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    private static void report(java.util.function.Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }
}
