package updater.fetchdata.eastmoney;

import updater.db.DBConnection;
import updater.db.DataTableManager;
import updater.fetchdata.StockCodeSource;
import updater.fetchdata.tdx.kdata.TdxStockData;
import updater.log.Log;
import updater.mount.MountConfig;
import updater.util.ItemProgress;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 备用日线来源：直接从东方财富的历史行情接口取日线，写进本地个股库。
 *
 * <p><b>为什么需要它</b>：「盘后数据下载」和「补充历史记录」原来只认本地数据来源的文件 ——
 * 机器上没有本地数据来源、或者没做过盘后下载，这两个任务就彻底做不了，一条数据也拿不到。
 * 这个类提供一条不依赖任何本地安装的路径，作为备份来源。</p>
 *
 * <p><b>口径必须和本地数据来源那条一致</b>，否则同一个库里会混进两套价格：
 * <ul>
 *   <li>取<b>不复权</b>价（{@code fqt=0}）。本地 {@code T_D_Day} 存的就是原始价，
 *       复权在除权表里另行处理</li>
 *   <li>价格按「分」入库，成交量从「手」换成「股」，成交额单位是「元」——
 *       跟 {@code TdxDayDataReader} 读出来的完全一样</li>
 * </ul></p>
 *
 * <p>逐只请求，全市场五千多只要几分钟到十几分钟，比读本地文件慢得多。
 * 它只在用户明确选了「东方财富」这个来源时才跑。</p>
 */
public final class EastMoneyDayFetcher {

    /** 东财单只一个请求，并发别开太大，容易被打回 */
    private static final int MAX_THREADS = 6;
    private static final int MAX_ATTEMPTS = 3;
    private static final String API = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String FIELDS = "&fields1=f1,f2,f3,f4,f5,f6"
            + "&fields2=f51,f52,f53,f54,f55,f56,f57&klt=101&fqt=0";
    private static final String REFERER = "https://quote.eastmoney.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 数据文件夹不可用时的统一提示 */
    public static final String NO_DATA_DIR_MESSAGE =
            "数据文件夹不可用，没法写入日线。\n\n"
                    + "请在上方「数据文件夹」里选一个可写的目录。";

    /** 一次「从东财取日线」的结果 */
    public static class Result {
        /** 本轮要处理的股票数 */
        public int codeCount;
        /** 真正写进库的只数 */
        public int updatedStocks;
        /** 写进库的日线条数 */
        public int rowsInserted;
        /** 新建的库文件数 */
        public int createdStocks;
        /** 没有数据的只数（退市 / 停牌 / 东财没这只） */
        public int skippedStocks;
        /** 失败的只数 */
        public int failedStocks;
        /** 本地库目录 */
        public String dbDir = "";
        public long elapsedMillis;
    }

    private EastMoneyDayFetcher() {
    }

    /**
     * 跑一轮。
     *
     * @param log          进度输出，null 打到控制台
     * @param items        逐只台账，null 表示不需要
     * @param cancel       停止标志，可为 null
     * @param onlyCodes    只处理这些代码（续跑 / 重试），null = 全部
     * @param days         只取最近多少个交易日；{@code <= 0} 表示取全部历史
     * @param createMissing true = 本地没有库的股票顺手建库（盘后下载）；
     *                      false = 本地没有库就跳过（补历史，跟本地数据来源那条一致）
     */
    public static Result run(Consumer<String> log, ItemProgress items, AtomicBoolean cancel,
                             Collection<String> onlyCodes, int days, boolean createMissing)
            throws Exception {
        return run(log, items, cancel, onlyCodes, days, createMissing, null);
    }

    /**
     * 跑一轮，代码清单可以外部指定（「指数数据」任务就靠这个把指数塞进同一套取数 / 建库流程）。
     *
     * @param explicitCodes 非空时只用这份清单，不再去本地库 / 名称库 / vipdoc 里推断；
     *                      null = 原来那套自动推断
     */
    public static Result run(Consumer<String> log, ItemProgress items, AtomicBoolean cancel,
                             Collection<String> onlyCodes, int days, boolean createMissing,
                             Collection<String> explicitCodes)
            throws Exception {
        final Consumer<String> out = log == null ? System.out::println : log;
        final ItemProgress sink = items == null ? ItemProgress.NONE : items;

        String dbDir = MountConfig.currentDbDir();
        if (dbDir == null || dbDir.trim().isEmpty()) {
            throw new IllegalStateException(NO_DATA_DIR_MESSAGE);
        }
        File dir = new File(dbDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("股票库目录不存在，且无法创建: " + dir.getAbsolutePath()
                    + "\n请检查该路径是否可写。");
        }

        List<String> codes = new ArrayList<String>();
        String sourceLabel;
        if (explicitCodes != null) {
            // 指数这条线不猜清单：传什么取什么，库里没有就建
            for (String code : explicitCodes) {
                if (onlyCodes == null || onlyCodes.contains(code)) {
                    codes.add(code);
                }
            }
            sourceLabel = "任务清单";
        } else {
            StockCodeSource.Result source = StockCodeSource.resolve(dir);
            if (source.isEmpty()) {
                throw new IllegalStateException("本地没有股票数据库，也找不到可用的股票代码清单。\n"
                        + "请先用「补最新价格」建库，或在数据文件夹里放一个股票名称库。");
            }
            for (String code : source.codes) {
                if (onlyCodes == null || onlyCodes.contains(code)) {
                    codes.add(code);
                }
            }
            sourceLabel = source.describe();
        }
        sink.planned(codes);

        Result result = new Result();
        result.dbDir = dir.getAbsolutePath();
        result.codeCount = codes.size();
        long start = System.currentTimeMillis();

        out.accept("更新范围: " + (days > 0 ? "最近 " + days + " 个交易日" : "全部历史"));
        out.accept("代码清单: " + codes.size() + " 项（" + sourceLabel + "）");
        out.accept("本地股票库目录: " + result.dbDir);
        if (!createMissing) {
            out.accept("本地没有库的股票会跳过（本次不建库）");
        }
        out.accept("逐只请求比读本地文件慢；全市场大约 "
                + Math.max(1, codes.size() / 900) + " 分钟，中途可以点停止");

        final AtomicInteger done = new AtomicInteger();
        final int reportStep = Math.max(1, codes.size() / 20);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(MAX_THREADS, Math.max(1, codes.size()))));
        List<Future<?>> futures = new ArrayList<Future<?>>(codes.size());
        try {
            for (final String code : codes) {
                if (stopped(cancel)) {
                    break;
                }
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        syncOne(code, dir, days, createMissing, out, sink, cancel, result);
                        int finished = done.incrementAndGet();
                        if (finished % reportStep == 0) {
                            out.accept("进度 " + finished + " / " + codes.size()
                                    + "，已写入 " + result.rowsInserted + " 条");
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    // 单只失败已经在 syncOne 里记过台账，这里不再打断整批
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        result.elapsedMillis = System.currentTimeMillis() - start;
        String summary = "东方财富日线写入完成：" + result.updatedStocks + "/" + result.codeCount + " 只，"
                + "共 " + result.rowsInserted + " 条"
                + (result.createdStocks > 0 ? "（新建库 " + result.createdStocks + " 个）" : "")
                + (result.skippedStocks > 0 ? "，跳过 " + result.skippedStocks + " 只" : "")
                + (result.failedStocks > 0 ? "，失败 " + result.failedStocks + " 只" : "")
                + "，用时 " + (result.elapsedMillis / 1000) + " 秒";
        out.accept(summary);
        Log.info(summary);
        return result;
    }

    /** 处理一只股票：取日线 → 比对本地已有日期 → 写库 */
    private static void syncOne(String marketCode, File dbDir, int days, boolean createMissing,
                                Consumer<String> out, ItemProgress items, AtomicBoolean cancel,
                                Result result) {
        if (stopped(cancel)) {
            return;
        }
        File dbFile = new File(dbDir, marketCode + ".db");
        boolean exists = dbFile.isFile();
        if (!exists && !createMissing) {
            items.skip(marketCode, "本地没有这只的库（本次不建库）");
            synchronized (result) {
                result.skippedStocks++;
            }
            return;
        }

        items.begin(marketCode);
        Connection conn = null;
        try {
            List<TdxStockData> bars = fetch(marketCode, days);
            if (bars.isEmpty()) {
                items.skip(marketCode, "东财没有这只的日线（可能已退市或长期停牌）");
                synchronized (result) {
                    result.skippedStocks++;
                }
                return;
            }

            conn = DBConnection.connect(marketCode, true);
            DataTableManager table = new DataTableManager();
            table.setConn(conn);
            table.createDataTable(marketCode);

            List<TdxStockData> toWrite;
            int existing = 0;
            if (days > 0) {
                // 盘后下载：最近 N 天用 OR REPLACE 覆盖写，价量有修订时以最新一次为准
                toWrite = bars;
            } else {
                Set<Long> has = loadExistingTimes(conn);
                existing = has.size();
                toWrite = new ArrayList<TdxStockData>(bars.size());
                for (TdxStockData bar : bars) {
                    if (!has.contains(Long.valueOf(bar.getDate()))) {
                        toWrite.add(bar);
                    }
                }
                if (toWrite.isEmpty()) {
                    items.skip(marketCode, "本地已是最全，没有要补的日期");
                    return;
                }
            }

            conn.setAutoCommit(false);
            int rows = table.insertBatch(toWrite);
            conn.commit();

            synchronized (result) {
                result.updatedStocks++;
                result.rowsInserted += rows;
                if (!exists) {
                    result.createdStocks++;
                }
            }
            items.done(marketCode, days > 0
                    ? "写入 " + rows + " 条（最近 " + days + " 个交易日）"
                    : "补入 " + rows + " 条（原有 " + existing + " 条）");
        } catch (Throwable t) {
            rollbackQuietly(conn);
            String reason = t.getMessage() == null ? t.toString() : t.getMessage();
            out.accept("  " + marketCode + " 失败: " + reason);
            Log.error("东方财富日线 " + marketCode + " 失败: " + reason);
            synchronized (result) {
                result.failedStocks++;
            }
            items.fail(marketCode, reason);
        } finally {
            DBConnection.closeQuietly(conn);
        }
    }

    /** 拉一只股票的日线；{@code days <= 0} 拉全部 */
    private static List<TdxStockData> fetch(String marketCode, int days) throws IOException {
        StringBuilder url = new StringBuilder(API)
                .append("?secid=").append(secId(marketCode))
                .append(FIELDS);
        if (days > 0) {
            // 东财没有「只要最近 N 条」这种参数（lmt 单独用会直接返回 data:null），
            // 只能给起始日期，返回后再截最后 N 条
            url.append("&beg=").append(beginDate(days)).append("&end=20500101");
        } else {
            url.append("&beg=0&end=20500101");
        }
        String body = request(url.toString());
        List<TdxStockData> bars = parseKlines(body);
        if (days > 0 && bars.size() > days) {
            return new ArrayList<TdxStockData>(bars.subList(bars.size() - days, bars.size()));
        }
        return bars;
    }

    /**
     * 「最近 N 个交易日」对应的起始日期（yyyyMMdd）。
     *
     * <p>多留 1.8 倍加 10 天的余量：周末、长假、停牌都要覆盖，宁可多取几条再截尾。</p>
     */
    private static String beginDate(int days) {
        long span = (long) (days * 1.8) + 10;
        java.time.LocalDate begin = java.time.LocalDate.now().minusDays(span);
        return begin.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * 东财返回：
     * <pre>
     * {"data":{"code":"600000","name":"浦发银行","klines":[
     *   "2024-01-02,10.50,10.60,10.70,10.45,123456,130000000.00,2.31,1.05,0.11,0.35", ...]}}
     * </pre>
     * 每行依次是 日期、开、收、高、低、成交量(手)、成交额(元)、振幅、涨跌幅、涨跌额、换手率。
     */
    private static List<TdxStockData> parseKlines(String body) {
        List<TdxStockData> bars = new ArrayList<TdxStockData>();
        if (body == null || body.trim().isEmpty()) {
            return bars;
        }
        JSONObject root;
        try {
            root = JSONObject.parseObject(body);
        } catch (RuntimeException e) {
            return bars;
        }
        JSONObject data = root == null ? null : root.getJSONObject("data");
        JSONArray klines = data == null ? null : data.getJSONArray("klines");
        if (klines == null) {
            return bars;
        }
        for (int i = 0; i < klines.size(); i++) {
            String line = klines.getString(i);
            if (line == null) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 7) {
                continue;
            }
            int date = toDate(parts[0]);
            if (date <= 0) {
                continue;
            }
            TdxStockData bar = new TdxStockData();
            bar.setDate(date);
            bar.setOpen((float) parseDouble(parts[1]));
            bar.setClose((float) parseDouble(parts[2]));
            bar.setHigh((float) parseDouble(parts[3]));
            bar.setLow((float) parseDouble(parts[4]));
            // 东财是「手」，本地数据来源那条链路读出来是「股」/「份」；
            // 基金同样是「手」（100 份），×100 后与个股口径一致
            bar.setVolume((long) Math.round(parseDouble(parts[5]) * 100));
            bar.setAmount((float) parseDouble(parts[6]));
            bars.add(bar);
        }
        return bars;
    }

    /** 2024-01-02 → 20240102 */
    private static int toDate(String text) {
        if (text == null) {
            return 0;
        }
        String digits = text.replace("-", "").replace("/", "").trim();
        if (digits.length() != 8) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
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

    /** 东财的证券标识：1.600000 沪市，0.000001 深市 / 北交所 */
    private static String secId(String marketCode) {
        String code = marketCode == null ? "" : marketCode.trim().toLowerCase();
        String digits = code.length() > 6 ? code.substring(code.length() - 6) : code;
        return (code.startsWith("sh") ? "1." : "0.") + digits;
    }

    private static String request(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return get(url);
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(300L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last == null ? new IOException("请求失败") : last;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("东财日线接口返回 HTTP " + status);
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream buf = new ByteArrayOutputStream(16384)) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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
