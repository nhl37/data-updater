package updater.fetchdata;

import updater.db.DBConnection;
import updater.db.DataTableManager;
import updater.fetchdata.eastmoney.EastMoneyDayFetcher;
import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.fetchdata.tdx.kdata.TdxDayDataReader;
import updater.fetchdata.tdx.kdata.TdxStockData;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 「指数数据」的本地线路：读本地数据来源 {@code vipdoc/{sh,sz,bj}/lday} 里指数的 .day。
 *
 * <p><b>为什么单独一个类</b>：指数在本地数据来源里是有的（{@code sh000001.day}、
 * {@code sz399001.day}、{@code bj899050.day} 都在，跟个股同一个目录），但
 * {@link ReaderFromTdx#codeOf} 刻意把指数排除在个股清单之外 —— 指数不能参与选股、
 * 不能被查缺补漏当股票对账。所以「指数数据」这个任务原来只能走在线那条线（东财），
 * 本地文件明明在那儿却用不上。这里补上本地这一档，取数口径跟个股那条链路一致。</p>
 *
 * <p><b>只补缺的日期，不整表重写</b>：本地文件的历史深度通常短于线上（本机
 * {@code sh000001.day} 从 2019 年起约 1281 条，而库里是 1990 年起的 8764 条）。
 * 覆盖写会把线上补回来的历史截短，所以只写库里没有的日期。</p>
 *
 * <p><b>成交量要 ×100</b>：本地 .day 里的成交量单位是「手」，而库里这批指数的历史
 * 是东财那条线写进去的（东财返回「手」，写库时 ×100 存成「股」）。不乘这 100，
 * 同一个库里老数据的量会是新数据的 100 倍 —— 不报错，只是量能整体偏小。
 * 实测 2026-11-13 上证指数：文件 {@code vol=472293657}，库 {@code VOL=47229365700}。</p>
 */
public final class IndexLocalFetcher {

    /** 一次「从本地文件取指数」的结果 */
    public static class Result {
        /** 本轮要处理的指数数 */
        public int codeCount;
        /** 真正写进库的指数数 */
        public int updatedIndexes;
        /** 库里已是最全、没有要补的日期 */
        public int upToDateIndexes;
        /** 写进库的日线条数 */
        public int rowsInserted;
        /** 新建的库文件数 */
        public int createdIndexes;
        /** 本地没有日线文件、跳过的指数 */
        public int skippedIndexes;
        /** 失败的指数 */
        public int failedIndexes;
        /** 本地库目录 */
        public String dbDir = "";
        /** 本地数据来源的 vipdoc 目录 */
        public String vipdocDir = "";
        public long elapsedMillis;

        /** 本地一个都没取到、也没有「已是最全」的 —— 该让上层问要不要换备用线路 */
        boolean nothingLocal() {
            return updatedIndexes == 0 && upToDateIndexes == 0;
        }
    }

    private IndexLocalFetcher() {
    }

    /**
     * 跑一轮。
     *
     * @param log          进度输出，null 打到控制台
     * @param items        逐只台账，null 表示不需要
     * @param cancel       停止标志，可为 null
     * @param onlyCodes    只处理这些代码（续跑 / 重试），null = 全部
     * @param indexCodes   要取的指数清单（来自 {@link IndexCodes}），非空
     * @throws IllegalStateException 本地数据来源不可用，或者清单里的指数一个文件都没找到
     */
    public static Result run(Consumer<String> log, ItemProgress items, AtomicBoolean cancel,
                             Collection<String> onlyCodes, Collection<String> indexCodes)
            throws Exception {
        final Consumer<String> out = log == null ? System.out::println : log;
        final ItemProgress sink = items == null ? ItemProgress.NONE : items;

        String dbDir = MountConfig.currentDbDir();
        if (dbDir == null || dbDir.trim().isEmpty()) {
            throw new IllegalStateException(EastMoneyDayFetcher.NO_DATA_DIR_MESSAGE);
        }
        File dbRoot = new File(dbDir);
        if (!dbRoot.isDirectory() && !dbRoot.mkdirs()) {
            throw new IllegalStateException("股票库目录不存在，且无法创建: " + dbRoot.getAbsolutePath()
                    + "\n请检查该路径是否可写。");
        }
        // 目录没配 / 没有 vipdoc 时这里抛出去，上层会问「要不要换备用线路」
        File vipdoc = TdxDataDir.requireVipdoc();

        List<String> codes = new ArrayList<String>();
        if (indexCodes != null) {
            for (String code : indexCodes) {
                if (onlyCodes == null || onlyCodes.contains(code)) {
                    codes.add(normalize(code));
                }
            }
        }
        sink.planned(codes);

        Result result = new Result();
        result.dbDir = dbRoot.getAbsolutePath();
        result.vipdocDir = vipdoc.getAbsolutePath();
        result.codeCount = codes.size();
        long start = System.currentTimeMillis();

        out.accept("代码清单: " + codes.size() + " 个指数（任务清单）");
        out.accept("本地数据来源目录: " + result.vipdocDir);
        out.accept("本地股票库目录: " + result.dbDir);
        out.accept("只补库里没有的日期；本地文件的历史比线上短，不会因此截短既有数据");

        for (String code : codes) {
            if (stopped(cancel)) {
                out.accept("收到停止请求，剩下的指数不再处理。已完成的部分都在台账里。");
                break;
            }
            syncOne(code, vipdoc, dbRoot, out, sink, result);
        }

        result.elapsedMillis = System.currentTimeMillis() - start;
        String summary = "本地指数日线写入完成：" + result.updatedIndexes + "/" + result.codeCount + " 个"
                + (result.upToDateIndexes > 0 ? "（另有 " + result.upToDateIndexes + " 个已是最新）" : "")
                + "，共 " + result.rowsInserted + " 条"
                + (result.createdIndexes > 0 ? "（新建库 " + result.createdIndexes + " 个）" : "")
                + (result.skippedIndexes > 0 ? "，跳过 " + result.skippedIndexes + " 个" : "")
                + (result.failedIndexes > 0 ? "，失败 " + result.failedIndexes + " 个" : "")
                + "，用时 " + (result.elapsedMillis / 1000) + " 秒";
        out.accept(summary);
        Log.info(summary);

        // 一个都没落地（文件全缺 / 全都读不出数据）：交给上层决定要不要切备用线路。
        // 「已是最全」不算失败 —— 那说明本地这条路是通的，只是这次没新数据。
        if (result.nothingLocal() && !stopped(cancel)) {
            throw new IllegalStateException("本地数据来源里没有可用的指数日线文件（共 "
                    + result.codeCount + " 个都没取到）。\n"
                    + "请确认「路径设置 → 本地数据来源」指向的是行情软件的安装目录，"
                    + "并且至少做过一次盘后数据下载。");
        }
        return result;
    }

    /** 处理一个指数：读本地 .day → 比对库里已有日期 → 只写缺的 */
    private static void syncOne(String code, File vipdoc, File dbRoot, Consumer<String> out,
                                ItemProgress items, Result result) {
        String market = code.length() > 2 ? code.substring(0, 2) : "";
        File file = new File(new File(new File(vipdoc, market), "lday"),
                code + ".day");
        if (!"sh".equals(market) && !"sz".equals(market) && !"bj".equals(market)) {
            items.skip(code, "不认识的指数代码前缀: " + market);
            result.skippedIndexes++;
            return;
        }
        if (!file.isFile()) {
            items.skip(code, "本地没有这个指数的日线文件（" + market + "/lday/" + code + ".day）");
            result.skippedIndexes++;
            return;
        }
        // 防呆：清单里万一手滑写了个个股代码，这里拦住，不要往指数库外扩散
        if (ReaderFromTdx.indexCodeOf(market, file.getName()) == null) {
            items.skip(code, "不是指数代码，跳过");
            result.skippedIndexes++;
            return;
        }

        items.begin(code);
        Connection conn = null;
        try {
            boolean exists = new File(dbRoot, code + ".db").isFile();

            List<TdxStockData> bars = TdxDayDataReader.readAllDay(file.getPath());
            if (bars.isEmpty()) {
                items.skip(code, "本地日线文件里没有数据");
                result.skippedIndexes++;
                return;
            }

            conn = DBConnection.connect(code, true);
            DataTableManager table = new DataTableManager();
            table.setConn(conn);
            table.createDataTable(code);

            Set<Long> existing = loadExistingTimes(conn);
            List<TdxStockData> missing = new ArrayList<TdxStockData>(bars.size());
            for (TdxStockData bar : bars) {
                if (!existing.contains(Long.valueOf(bar.getDate()))) {
                    // 本地是「手」，库里的历史是线上写的「股」，×100 才对得上（见类注释）
                    bar.setVolume(bar.getVolume() * 100L);
                    missing.add(bar);
                }
            }
            if (missing.isEmpty()) {
                items.skip(code, "本地已是最全，没有要补的日期");
                result.upToDateIndexes++;
                return;
            }

            conn.setAutoCommit(false);
            int rows = table.insertBatch(missing);
            conn.commit();

            result.updatedIndexes++;
            result.rowsInserted += rows;
            if (!exists) {
                result.createdIndexes++;
            }
            items.done(code, (exists ? "补入 " : "新建库，写入 ") + rows + " 条（原有 "
                    + existing.size() + " 条）");
        } catch (Throwable t) {
            rollbackQuietly(conn);
            String reason = t.getMessage() == null ? t.toString() : t.getMessage();
            out.accept("  " + code + " 失败: " + reason);
            Log.error("本地指数日线 " + code + " 失败: " + reason);
            result.failedIndexes++;
            items.fail(code, reason);
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    private static Set<Long> loadExistingTimes(Connection conn) throws SQLException {
        Set<Long> times = new HashSet<Long>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT TIME FROM T_D_Day");
            while (rs.next()) {
                times.add(Long.valueOf(rs.getLong(1)));
            }
        } catch (SQLException e) {
            // 表还不存在 = 一条都没有，按空集处理
            return times;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignore) {
                }
            }
        }
        return times;
    }

    /** sh000001 这种写法；裸代码原样返回（清单是固定写法，这里只是兜底） */
    private static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean stopped(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    private static void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException ignore) {
        }
    }
}
