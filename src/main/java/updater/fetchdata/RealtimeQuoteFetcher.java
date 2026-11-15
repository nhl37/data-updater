package updater.fetchdata;

import updater.log.Log;

import javax.net.ssl.SSLException;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 盘中行情获取：直接请求腾讯免费行情接口，替代原来的 read_price.py + easyquotation。
 *
 * <p>发布包里没有 Python 解释器，Windows 上点「更新数据-补最新价格」会直接报找不到 python；
 * 这条链路改成纯 Java 后不再依赖任何外部运行时。输出 CSV 的列与原脚本一致，
 * 后面的 {@link DbDataUpdater#updatePriceByFile()} 不需要改动。</p>
 */
public final class RealtimeQuoteFetcher {

    /** 腾讯单次请求上限，easyquotation 的 tencent 也是 60 */
    private static final int BATCH_SIZE = 60;
    /** 新浪一次带多少只：实测 60 只很稳，再多响应会变长 */
    private static final int SINA_BATCH_SIZE = 60;
    /** 东财 ulist 一次带 100 个 secid 没问题，请求数少一半 */
    private static final int EM_BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_THREADS = 8;
    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final String API = "qt.gtimg.cn/q=";
    private static final String SINA_API = "https://hq.sinajs.cn/";
    private static final String SINA_REFERER = "https://finance.sina.com.cn";
    private static final String EM_API = "https://push2.eastmoney.com/api/qt/ulist.np/get"
            + "?fltt=2&fields=f2,f5,f6,f12,f13,f14,f15,f16,f17&secids=";
    private static final String EM_REFERER = "https://quote.eastmoney.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 线路枚举。第一条是默认线路，其余只当备份 —— 输出 CSV 的列完全一致 */
    public enum Kind {
        TENCENT, SINA, EASTMONEY
    }

    /**
     * 部分 Windows 机器的证书链被安全软件改过，HTTPS 会 PKIX 失败；
     * 这时整场退回 HTTP（easyquotation 原本也走 HTTP）。
     */
    private static volatile boolean useHttps = true;

    private RealtimeQuoteFetcher() {
    }

    public static int fetchToCsv(String stockDir, String dataSource, String outputFile) throws IOException {
        return fetchToCsv(stockDir, dataSource, outputFile, null);
    }

    /**
     * 拉取 stockDir 里所有 .db 对应股票的实时行情，写成 CSV。
     *
     * @param stockDir   股票数据目录（挂载目录），用文件名当股票代码
     * @param dataSource easyquotation.properties 里的数据源，目前只支持 tencent/qq
     * @param outputFile 输出 CSV 路径，父目录不存在会自动创建
     * @param log        进度输出，null 表示打印到控制台
     * @return 写入 CSV 的股票条数
     * @throws IOException 目录为空、数据源不支持、完全取不到数据
     */
    public static int fetchToCsv(String stockDir, String dataSource, String outputFile,
                                 Consumer<String> log) throws IOException {
        return fetchToCsv(new File(stockDir), dataSource, outputFile, null, log);
    }

    /**
     * 同上，但股票代码由调用方给定。
     *
     * <p>本地还没有股票库时目录里没有 .db 可当代码清单，代码只能来自
     * 「挂载的名称库」或「本地数据来源里的日线文件名」，见
     * {@link StockCodeSource}。</p>
     *
     * @param codes 股票代码；null 或空表示按 stockDir 里的 .db 文件名取
     */
    public static int fetchToCsv(File stockDir, String dataSource, String outputFile,
                                 List<String> codes, Consumer<String> log) throws IOException {
        Consumer<String> out = log == null ? System.out::println : log;
        Kind kind = kindOf(dataSource);

        List<String> useCodes = (codes == null || codes.isEmpty()) ? listStockCodes(stockDir) : codes;
        if (useCodes.isEmpty()) {
            throw new IOException("股票目录里没有 .db 文件: "
                    + (stockDir == null ? "" : stockDir.getAbsolutePath()));
        }
        out.accept("找到 " + useCodes.size() + " 个股票代码，开始获取行情");

        Map<String, Quote> quotes = fetchAll(kind, buildQueries(kind, useCodes), out);
        if (quotes.isEmpty()) {
            throw new IOException("行情线路连接失败（每批已自动重试 "
                    + MAX_ATTEMPTS + " 次），没有取到任何数据");
        }

        int rows = writeCsv(outputFile, quotes);
        Log.info("RealtimeQuoteFetcher-end, 写入 " + rows + " 条 / 请求 " + useCodes.size() + " 只");
        out.accept("行情已保存: " + outputFile + "（" + rows + " 条）");
        return rows;
    }

    /**
     * 数据源名 → 来源枚举。
     *
     * <p>{@code qq} 是 easyquotation 里的老写法，等同于 tencent，保留兼容。</p>
     */
    private static Kind kindOf(String dataSource) throws IOException {
        String source = dataSource == null ? "" : dataSource.trim().toLowerCase(Locale.ROOT);
        if (source.equals("tencent") || source.equals("qq") || source.isEmpty()) {
            return Kind.TENCENT;
        }
        if (source.equals("sina")) {
            return Kind.SINA;
        }
        if (source.equals("eastmoney") || source.equals("em") || source.equals("dongc")) {
            return Kind.EASTMONEY;
        }
        throw new IOException("行情线路参数无法识别，请检查行情配置");
    }

    private static Charset charsetOf(Kind kind) {
        return kind == Kind.EASTMONEY ? UTF8 : GBK;
    }

    /** 目录里的 .db 文件名＝股票代码（本地已有库时的代码清单） */
    public static List<String> listStockCodes(File dir) {
        List<String> codes = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return codes;
        }
        File[] dbFiles = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".db"));
        if (dbFiles == null) {
            return codes;
        }
        for (File dbFile : dbFiles) {
            String name = dbFile.getName();
            String code = name.substring(0, name.length() - 3);
            if (!code.trim().isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static List<String> buildQueries(Kind kind, List<String> codes) {
        int batchSize = kind == Kind.EASTMONEY ? EM_BATCH_SIZE
                : (kind == Kind.SINA ? SINA_BATCH_SIZE : BATCH_SIZE);
        List<String> queries = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int inBatch = 0;
        for (String code : codes) {
            if (inBatch == batchSize) {
                queries.add(sb.toString());
                sb.setLength(0);
                inBatch = 0;
            }
            if (inBatch > 0) {
                sb.append(',');
            }
            sb.append(kind == Kind.EASTMONEY ? secId(code) : toMarketCode(code));
            inBatch++;
        }
        if (inBatch > 0) {
            queries.add(sb.toString());
        }
        return queries;
    }

    /**
     * 东财的证券标识：{@code 1.600000} 表示沪市、{@code 0.000001} 表示深市 / 北交所。
     *
     * <p>市场位只认「是不是沪」，跟 {@link #marketPrefix} 的口径一致，
     * 这样北交所不会被错标成沪市。</p>
     */
    private static String secId(String code) {
        String market = toMarketCode(code);
        String digits = market.length() > 6 ? market.substring(market.length() - 6) : market;
        return (market.startsWith("sh") ? "1." : "0.") + digits;
    }

    /**
     * 规范成「市场前缀 + 6 位」的写法：sz000830 -&gt; sz000830；600000 -&gt; sh600000
     * （与 easyquotation 的 get_stock_type 规则一致）。
     *
     * <p>本地库文件名就是这个格式，新建库时也必须按这个规则命名，否则和
     * 本地日线导入、K线读取的代码对不上。</p>
     */
    public static String toMarketCode(String code) {
        String trimmed = code.trim();
        String digits = trimmed.length() > 6 ? trimmed.substring(trimmed.length() - 6) : trimmed;
        return marketPrefix(trimmed) + digits;
    }

    private static String marketPrefix(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sh") || lower.startsWith("sz")
                || lower.startsWith("zz") || lower.startsWith("bj")) {
            return lower.substring(0, 2);
        }
        if (code.startsWith("43") || code.startsWith("83")
                || code.startsWith("87") || code.startsWith("92")) {
            return "bj";
        }
        if (code.startsWith("5") || code.startsWith("6") || code.startsWith("7") || code.startsWith("9")
                || code.startsWith("110") || code.startsWith("113") || code.startsWith("118")
                || code.startsWith("132") || code.startsWith("204")) {
            return "sh";
        }
        return "sz";
    }

    private static Map<String, Quote> fetchAll(final Kind kind, List<String> queries,
                                               final Consumer<String> out) {
        final Map<String, Quote> all = new LinkedHashMap<>();
        final int total = queries.size();
        final int threads = Math.max(1, Math.min(MAX_THREADS, total));
        final int reportStep = Math.max(1, total / 10);
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Map<String, Quote>>> futures = new ArrayList<>(total);
        try {
            for (final String query : queries) {
                futures.add(pool.submit(() -> {
                    Map<String, Quote> part;
                    try {
                        part = fetchBatch(kind, query);
                    } catch (IOException e) {
                        failed.incrementAndGet();
                        System.out.println("行情批次失败: " + e.getMessage());
                        part = Collections.emptyMap();
                    }
                    int finished = done.incrementAndGet();
                    if (finished % reportStep == 0 || finished == total) {
                        out.accept("行情获取进度 " + finished + " / " + total + " 批");
                    }
                    return part;
                }));
            }
            for (Future<Map<String, Quote>> future : futures) {
                try {
                    all.putAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return all;
                } catch (ExecutionException e) {
                    System.out.println("行情批次异常: " + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (failed.get() > 0) {
            System.out.println("行情获取结束，失败批次 " + failed.get() + " / " + total);
        }
        return all;
    }

    private static Map<String, Quote> fetchBatch(Kind kind, String query) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Quote> quotes = parse(kind, download(kind, query));
                if (quotes.isEmpty()) {
                    throw new IOException("返回数据为空");
                }
                return quotes;
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
        throw last;
    }

    /** 拼出这一批的完整 URL（腾讯/新浪是明文清单，东财是 secids 参数） */
    private static String urlOf(Kind kind, String query) {
        switch (kind) {
            case SINA:
                return SINA_API + "list=" + query;
            case EASTMONEY:
                return EM_API + query;
            default:
                return "https://" + API + query;
        }
    }

    private static String refererOf(Kind kind) {
        switch (kind) {
            case SINA:
                return SINA_REFERER;
            case EASTMONEY:
                return EM_REFERER;
            default:
                return "";
        }
    }

    private static String download(Kind kind, String query) throws IOException {
        if (useHttps) {
            try {
                return get(urlOf(kind, query), charsetOf(kind), refererOf(kind));
            } catch (SSLException e) {
                synchronized (RealtimeQuoteFetcher.class) {
                    if (useHttps) {
                        useHttps = false;
                        System.out.println("HTTPS 行情接口不可用（" + e.getMessage() + "），改用 HTTP");
                    }
                }
            }
        }
        // 只有腾讯有明文 HTTP 可退；新浪/东财强制 HTTPS，退不了就只能报错
        if (kind != Kind.TENCENT) {
            return get(urlOf(kind, query), charsetOf(kind), refererOf(kind));
        }
        return get("http://" + API + query, GBK, "");
    }

    private static String get(String url, Charset charset, String referer) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            // 新浪不带 Referer 直接 403；东财部分节点也认这个
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("线路返回 HTTP " + status);
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream buf = new ByteArrayOutputStream(16384)) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), charset);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Map<String, Quote> parse(Kind kind, String text) {
        switch (kind) {
            case SINA:
                return parseSina(text);
            case EASTMONEY:
                return parseEastMoney(text);
            default:
                return parseTencent(text);
        }
    }

    /**
     * 腾讯返回：v_sz000830="51~鲁西化工~000830~10.87~10.76~10.75~234963~...";
     * 用 ~ 分隔后：1 名称、3 现价、5 开盘、6 成交量(手)、33 最高、34 最低、37 成交额(万)。
     */
    private static Map<String, Quote> parseTencent(String text) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (String record : text.split(";")) {
            int eq = record.indexOf('=');
            if (eq <= 2) {
                continue;
            }
            String head = record.substring(0, eq).trim();
            if (!head.startsWith("v_")) {
                continue;
            }
            String body = record.substring(eq + 1).trim();
            if (body.startsWith("\"")) {
                body = body.substring(1);
            }
            int end = body.indexOf('"');
            if (end >= 0) {
                body = body.substring(0, end);
            }
            String[] fields = body.split("~", -1);
            if (fields.length < 38) {
                continue;
            }
            String now = number(fields[3]);
            if (now == null) {
                continue;
            }
            long volume = (long) (parse(fields[6]) * 100);
            long amount = (long) (parse(fields[37]) * 10000);
            quotes.put(head.substring(2), new Quote(
                    fields[1], now,
                    numberOrZero(fields[5]), numberOrZero(fields[33]), numberOrZero(fields[34]),
                    volume, amount));
        }
        return quotes;
    }

    /**
     * 新浪返回：var hq_str_sh600000="浦发银行,11.660,11.680,11.610,11.770,11.610,...";
     * 用 , 分隔后：0 名称、1 今开、2 昨收、3 现价、4 最高、5 最低、8 成交量(股)、9 成交额(元)。
     *
     * <p>新浪的成交量和成交额已经是「股」「元」，不用再换算（腾讯是「手」「万元」）。</p>
     */
    private static Map<String, Quote> parseSina(String text) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (String record : text.split(";")) {
            int eq = record.indexOf('=');
            if (eq <= 2) {
                continue;
            }
            String head = record.substring(0, eq).trim();
            int marker = head.lastIndexOf("hq_str_");
            if (marker < 0) {
                continue;
            }
            String code = head.substring(marker + "hq_str_".length());
            if (code.length() < 8) {
                continue;
            }
            String body = record.substring(eq + 1).trim();
            if (body.startsWith("\"")) {
                body = body.substring(1);
            }
            int end = body.indexOf('"');
            if (end >= 0) {
                body = body.substring(0, end);
            }
            String[] fields = body.split(",", -1);
            if (fields.length < 10) {
                continue;
            }
            String now = number(fields[3]);
            // 停牌当天新浪给 0.00，写进库会把收盘价砸成 0
            if (now == null || parse(now) <= 0) {
                continue;
            }
            quotes.put(toMarketCode(code), new Quote(
                    fields[0], now,
                    numberOrZero(fields[1]), numberOrZero(fields[4]), numberOrZero(fields[5]),
                    (long) parse(fields[8]), (long) parse(fields[9])));
        }
        return quotes;
    }

    /**
     * 东财返回（ulist，fltt=2 时字段是格式化后的数字）：
     * <pre>
     * {"data":{"total":2,"diff":[{"f2":11.61,"f5":749371,"f6":876210000.0,
     *                             "f12":"600000","f13":1,"f14":"浦发银行",
     *                             "f15":11.77,"f16":11.61,"f17":11.66}]}}
     * </pre>
     * f2 现价、f5 成交量(手)、f6 成交额(元)、f12 代码、f13 市场、f14 名称、
     * f15 最高、f16 最低、f17 今开。停牌时 f2 是字符串 "-"，直接跳过。
     */
    private static Map<String, Quote> parseEastMoney(String text) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return quotes;
        }
        com.alibaba.fastjson2.JSONObject root;
        try {
            root = com.alibaba.fastjson2.JSON.parseObject(text);
        } catch (RuntimeException e) {
            System.out.println("东财行情返回无法解析: " + e.getMessage());
            return quotes;
        }
        com.alibaba.fastjson2.JSONObject data = root == null ? null : root.getJSONObject("data");
        com.alibaba.fastjson2.JSONArray diff = data == null ? null : data.getJSONArray("diff");
        if (diff == null) {
            return quotes;
        }
        for (int i = 0; i < diff.size(); i++) {
            com.alibaba.fastjson2.JSONObject row = diff.getJSONObject(i);
            if (row == null) {
                continue;
            }
            String code = row.getString("f12");
            String now = number(row.getString("f2"));
            if (code == null || code.trim().isEmpty() || now == null || parse(now) <= 0) {
                continue;
            }
            String name = row.getString("f14");
            quotes.put(toMarketCode(code), new Quote(
                    name == null ? "" : name, now,
                    numberOrZero(row.getString("f17")),
                    numberOrZero(row.getString("f15")),
                    numberOrZero(row.getString("f16")),
                    (long) (parse(row.getString("f5")) * 100),
                    (long) parse(row.getString("f6"))));
        }
        return quotes;
    }

    private static String number(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            Double.parseDouble(trimmed);
            return trimmed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String numberOrZero(String value) {
        String parsed = number(value);
        return parsed == null ? "0" : parsed;
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int writeCsv(String outputFile, Map<String, Quote> quotes) throws IOException {
        Path path = Paths.get(outputFile).toAbsolutePath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        List<String> codes = new ArrayList<>(quotes.keySet());
        Collections.sort(codes);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // 与 read_price.py 一样带 BOM，Excel 直接打开不乱码
            writer.write('\uFEFF');
            writer.write("代码,名称,现价,开盘价,最高,最低,成交量,成交额\n");
            for (String code : codes) {
                Quote quote = quotes.get(code);
                writer.write(code);
                writer.write(',');
                writer.write(clean(quote.name));
                writer.write(',');
                writer.write(quote.now);
                writer.write(',');
                writer.write(quote.open);
                writer.write(',');
                writer.write(quote.high);
                writer.write(',');
                writer.write(quote.low);
                writer.write(',');
                writer.write(Long.toString(quote.volume));
                writer.write(',');
                writer.write(Long.toString(quote.amount));
                writer.write('\n');
            }
        }
        return codes.size();
    }

    /** 名称里出现逗号会把后面的价格列整体挪位，DbDataUpdater 是裸 split(",") */
    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        return name.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static final class Quote {
        final String name;
        final String now;
        final String open;
        final String high;
        final String low;
        final long volume;
        final long amount;

        Quote(String name, String now, String open, String high, String low, long volume, long amount) {
            this.name = name;
            this.now = now;
            this.open = open;
            this.high = high;
            this.low = low;
            this.volume = volume;
            this.amount = amount;
        }
    }
}
