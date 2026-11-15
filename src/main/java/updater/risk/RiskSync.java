package updater.risk;

import updater.db.FinanceDBManager;
import updater.db.VaultDb;
import updater.f10.F10Api;
import updater.f10.F10Store;
import updater.mount.MountConfig;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三份数据的同步，成本从低到高：
 *
 * <ol>
 *   <li>{@link #syncAnnouncements()} —— <b>不联网</b>。f10.db 里已经有两年的公告和情绪判定，
 *       这里只是按类别归并成风险事件，几秒钟跑完；</li>
 *   <li>{@link #syncLifts(int, int)} —— 联网拉解禁表（东财 {@code RPT_LIFT_STAGE}），
 *       一年区间约 1500 行 / 4 页，几秒钟；</li>
 *   <li>{@link #syncValuation(Progress)} —— <b>要读 5300 个个股库</b>，几十秒。
 *       它不联网，纯粹是本机计算（股本 × 收盘价 + 财报），所以随时可以重跑。</li>
 * </ol>
 *
 * <p>为什么不把估值做成日频历史序列：那需要每个交易日遍历 5300 个库，跑一次半小时起步，
 * 而「今天贵不贵」这个问题的 90% 用途（排序、筛选、和同行比）用最新快照就够了。
 * 真要做历史分位，等有了日频快照积累再算，别一上来就建一个跑不动的管道。</p>
 */
public final class RiskSync {

    private static final String REFERER = "https://data.eastmoney.com/";
    private static final int TIMEOUT_MS = 20000;
    private static final int LIFT_PAGE_SIZE = 500;

    /** 估值要读几千个库，留个进度与取消的口子 */
    public interface Progress {
        void step(int done, int total, String text);

        boolean cancelled();
    }

    /** 每算完一只回调一次；value 为 null 表示这只没有最新行情或股本 */
    public interface ItemDone {
        void done(String code, RiskStore.Valuation value);
    }

    public record Result(int rows, String note) {
    }

    private RiskSync() {
    }

    public static int today() {
        LocalDate now = LocalDate.now();
        return now.getYear() * 10000 + now.getMonthValue() * 100 + now.getDayOfMonth();
    }

    /**
     * yyyyMMdd 加减天数。
     *
     * <p><b>千万别直接做整数加减</b>：{@code 20261108 + 365 = 20261473}，月份 14 不存在，
     * 东财接口会回一句「日期格式有误」，查半天都以为是参数编码的问题。</p>
     */
    public static int plusDays(int date, int days) {
        return fromLocal(toLocal(date).plusDays(days));
    }

    public static int plusMonths(int date, int months) {
        return fromLocal(toLocal(date).plusMonths(months));
    }

    static LocalDate toLocal(int date) {
        return LocalDate.of(date / 10000, date / 100 % 100, date % 100);
    }

    static int fromLocal(LocalDate date) {
        return date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    // ==================================================================
    //  1. 公告 → 风险事件（离线）
    // ==================================================================

    /**
     * 把 f10.db 里判过情绪的利空公告归并成风险事件。
     *
     * <p>直接从 {@code SENTIMENT=-1} 的行取，不再重新判一遍情绪 —— 情绪口径以
     * {@code AnnouncementJudge} 为唯一出处，两处各判一次早晚会不一致。
     * 这里只做「按类别归并 + 补名称」。</p>
     */
    public static Result syncAnnouncements() {
        String path = F10Store.dbFilePath();
        File file = new File(path);
        if (!file.isFile()) {
            return new Result(0, "没有公告库（f10.db），先在有公告数据的环境里跑一次");
        }
        List<RiskEvent> events = new ArrayList<>();
        String sql = "SELECT CODE, NOTICE_DATE, TITLE, KEYWORD, WEIGHT FROM T_Announcement"
                + " WHERE SENTIMENT=-1";
        try (Connection conn = VaultDb.openCompat(file);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String code = CodeName.six(rs.getString(1));
                int date = rs.getInt(2);
                String title = rs.getString(3);
                String keyword = rs.getString(4);
                int weight = rs.getInt(5);
                RiskCategory category = classify(keyword, title);
                if (category == null) {
                    continue;
                }
                String name = nameFromTitle(title);
                if (name == null) {
                    name = CodeName.name(code);
                }
                int level = weight >= 3 ? 3 : weight >= 2 ? 2 : 1;
                events.add(new RiskEvent(code, name, date, category, level, title, "ann",
                        code + "-" + date + "-" + Math.abs(title == null ? 0 : title.hashCode())));
            }
        } catch (Exception e) {
            return new Result(0, "读公告库失败：" + e.getMessage());
        }
        RiskStore.replaceEvents("ann", events);
        RiskStore.putMeta("ann_at", String.valueOf(System.currentTimeMillis()));
        return new Result(events.size(), "已归并 " + events.size() + " 条利空公告");
    }

    /** 公告判定里的关键词优先，判不出来再按标题正则兜底 */
    private static RiskCategory classify(String keyword, String title) {
        if (keyword != null) {
            switch (keyword) {
                case "立案/处罚":
                    return RiskCategory.INVESTIGATION;
                case "退市风险":
                    return RiskCategory.DELIST;
                case "业绩预亏":
                    return RiskCategory.LOSS;
                case "计提减值":
                    return RiskCategory.IMPAIR;
                case "股权冻结":
                    return RiskCategory.FREEZE;
                case "违规事项":
                    return RiskCategory.FRAUD;
                case "交易所函件":
                    return RiskCategory.REGULATOR;
                case "诉讼/仲裁":
                    return RiskCategory.LITIGATION;
                case "补充质押/平仓风险":
                    return RiskCategory.PLEDGE;
                case "逾期/违约":
                    return RiskCategory.DEFAULT;
                case "减持":
                    return RiskCategory.REDUCE;
                default:
                    break;
            }
        }
        RiskCategory byTitle = RiskCategory.classify(title, keyword);
        // 归不到具体类的就不进风险表：宁可少标，也不要把「风险提示」这种套话堆进来
        return byTitle == RiskCategory.OTHER ? null : byTitle;
    }

    /** 公告标题形如「贵州茅台:关于…的公告」，冒号前就是简称 */
    private static String nameFromTitle(String title) {
        if (title == null) {
            return null;
        }
        int mark = title.indexOf(':');
        if (mark <= 0 || mark > 12) {
            return null;
        }
        String head = title.substring(0, mark).trim();
        return head.isEmpty() ? null : head;
    }

    // ==================================================================
    //  2. 限售解禁（联网）
    // ==================================================================

    /**
     * 拉取某区间的解禁明细，落 T_Lift，同时派生风险事件。
     *
     * <p>接口每页 500 行（实测），一年区间 4 页左右，顺序拉即可，不值得上线程池。
     * 单位的坑：{@code CURRENT_FREE_SHARES} 是<b>万股</b>、{@code LIFT_MARKET_CAP} 是<b>万元</b>、
     * 两个 RATIO 是<b>小数</b>（0.0974 表示 9.74%），不是百分数。</p>
     */
    public static Result syncLifts(int fromDate, int toDate) {
        String filter = "(FREE_DATE>='" + iso(fromDate) + "')(FREE_DATE<='" + iso(toDate) + "')";
        List<RiskStore.Lift> lifts = new ArrayList<>();
        List<RiskEvent> events = new ArrayList<>();
        try {
            int pages = 1;
            for (int page = 1; page <= pages; page++) {
                JSONObject root = JSONObject.parseObject(F10Api.get(liftUrl(page, filter), REFERER, TIMEOUT_MS));
                if (root == null || !root.getBooleanValue("success")) {
                    throw new IllegalStateException(root == null ? "无响应"
                            : String.valueOf(root.getString("message")));
                }
                JSONObject result = root.getJSONObject("result");
                if (result == null) {
                    break;
                }
                pages = result.getIntValue("pages");
                JSONArray data = result.getJSONArray("data");
                if (data == null) {
                    break;
                }
                for (int i = 0; i < data.size(); i++) {
                    JSONObject row = data.getJSONObject(i);
                    String code = CodeName.six(row.getString("SECURITY_CODE"));
                    String name = row.getString("SECURITY_NAME_ABBR");
                    int freeDate = number(row.getString("FREE_DATE"));
                    if (code.isEmpty() || freeDate <= 0) {
                        continue;
                    }
                    double shares = row.getDoubleValue("CURRENT_FREE_SHARES");
                    double cap = row.getDoubleValue("LIFT_MARKET_CAP");
                    double freeRatio = row.getDoubleValue("FREE_RATIO");
                    double totalRatio = row.getDoubleValue("TOTALSHARES_RATIO");
                    String type = row.getString("FREE_SHARES_TYPE");
                    lifts.add(new RiskStore.Lift(code, name, freeDate, shares, cap,
                            freeRatio, totalRatio, type == null ? "" : type));
                    int level = RiskEvent.liftLevel(freeRatio);
                    String title = "解禁 " + round(shares / 10000.0, 2) + " 亿股（占流通 "
                            + round(freeRatio * 100, 1) + "%）· " + type;
                    events.add(new RiskEvent(code, name, freeDate, RiskCategory.LIFT, level,
                            title, "lift", code + "-" + freeDate + "-" + type));
                }
            }
        } catch (Exception e) {
            return new Result(0, "拉取解禁数据失败：" + e.getMessage());
        }
        RiskStore.replaceLifts(lifts);
        RiskStore.replaceEvents("lift", events);
        RiskStore.putMeta("lift_range", fromDate + "-" + toDate);
        RiskStore.putMeta("lift_at", String.valueOf(System.currentTimeMillis()));
        return new Result(lifts.size(), "解禁 " + lifts.size() + " 条（" + iso(fromDate) + " ~ "
                + iso(toDate) + "）");
    }

    private static String liftUrl(int page, String filter) {
        return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                + "?reportName=RPT_LIFT_STAGE"
                + "&columns=SECURITY_CODE,SECURITY_NAME_ABBR,FREE_DATE,CURRENT_FREE_SHARES,"
                + "LIFT_MARKET_CAP,FREE_RATIO,TOTALSHARES_RATIO,FREE_SHARES_TYPE"
                + "&pageSize=" + LIFT_PAGE_SIZE
                + "&pageNumber=" + page
                + "&sortColumns=FREE_DATE&sortTypes=1"
                + "&filter=" + encode(filter);
    }

    private static String encode(String text) {
        return text.replace("'", "%27").replace(">", "%3E").replace("<", "%3C")
                .replace("=", "%3D").replace("(", "%28").replace(")", "%29")
                .replace(" ", "%20");
    }

    // ==================================================================
    //  3. 估值快照（本地计算）
    // ==================================================================

    /**
     * 算一遍全市场估值快照。
     *
     * <p>口径（四种 PE 并存，因为回答的是不同问题）：</p>
     * <ul>
     *   <li>总市值 = 总股本 × 最新收盘价；流通市值 = 流通A股 × 最新收盘价；</li>
     *   <li>PE(静) = 总市值 ÷ <b>上年年报</b>归母净利；</li>
     *   <li>PE(动) = 总市值 ÷（<b>本期累计</b>归母净利 × 年化系数），年化系数 0331→4、0630→2、0930→4/3、1231→1；</li>
     *   <li>PE(TTM) = 总市值 ÷ 归母净利TTM，TTM = 本期累计 + 上年年报 − 上年同期累计；</li>
     *   <li>PB = 总市值 ÷ 归母净资产，归母净资产 = 每股净资产 × 总股本
     *       （gpcw 的「所有者权益合计」含少数股东权益，用它算 PB 会系统性偏低）；</li>
     *   <li>ROE(加权) = gpcw 字段 280，<b>累计口径不年化</b>。</li>
     * </ul>
     *
     * <p>行情库里的 CLOSE 是<b>分</b>（145300 = 1453.00 元），必须除以 100 ——
     * 忘了除会让市值虚高 100 倍，而这种错在界面上看起来「数很大但很合理」。</p>
     */
    /** 要算估值的代码清单：更新器的台账先按它铺行 */
    public static List<String> valuationCodes() {
        return new ArrayList<>(loadShares().keySet());
    }

    public static Result syncValuation(Progress progress) {
        return syncValuation(progress, null);
    }

    public static Result syncValuation(Progress progress, ItemDone onItem) {
        Map<String, double[]> shares = loadShares();
        Map<String, double[]> finance = loadFinance();
        if (shares.isEmpty()) {
            return new Result(0, "没有股本表（T_D_ShareCapital），无法算市值");
        }
        List<String> codes = new ArrayList<>(shares.keySet());
        List<RiskStore.Valuation> values = new ArrayList<>();
        int total = codes.size();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "risk-valuation");
            t.setDaemon(true);
            return t;
        });
        try {
            List<String> futureCodes = new ArrayList<>(codes.size());
            List<Future<RiskStore.Valuation>> futures = new ArrayList<>(codes.size());
            for (String code : codes) {
                futureCodes.add(code);
                futures.add(pool.submit(() -> valueOf(code, shares.get(code), finance.get(code))));
            }
            for (int i = 0; i < futures.size(); i++) {
                RiskStore.Valuation value = futures.get(i).get();
                if (value != null) {
                    values.add(value);
                }
                if (onItem != null) {
                    onItem.done(futureCodes.get(i), value);
                }
                int now = done.incrementAndGet();
                if (progress != null && (now % 50 == 0 || now == total)) {
                    progress.step(now, total, "已算 " + now + " / " + total);
                    if (progress.cancelled()) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return new Result(values.size(), "计算估值中断：" + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
        RiskStore.replaceValuations(values);
        RiskStore.putMeta("valuation_at", String.valueOf(System.currentTimeMillis()));
        return new Result(values.size(), "估值快照 " + values.size() + " 只");
    }

    private static RiskStore.Valuation valueOf(String code, double[] share, double[] fin) {
        double[] bar = lastBar(CodeName.full(code));
        if (bar == null || bar[0] <= 0) {
            return null;
        }
        double close = bar[0];
        int date = (int) bar[1];
        double totalShares = share[0] > 0 ? share[0] : (fin == null ? 0 : financeVal(fin, 2));
        double floatShares = share[1];
        double totalCap = totalShares * close;
        double floatCap = floatShares > 0 ? floatShares * close : totalCap;
        int reportDate = fin == null ? 0 : (int) financeVal(fin, 6);
        double profitTtm = fin == null ? Double.NaN : financeVal(fin, 0);
        double equity = fin == null ? Double.NaN : financeVal(fin, 1);
        double profitStatic = fin == null ? Double.NaN : financeVal(fin, 3);
        double profitAnnual = fin == null ? Double.NaN : financeVal(fin, 4);
        double roeWeight = fin == null ? Double.NaN : financeVal(fin, 5);
        double pe = profitTtm > 0 && totalCap > 0 ? totalCap / profitTtm : Double.NaN;
        double pb = equity > 0 && totalCap > 0 ? totalCap / equity : Double.NaN;
        double peStatic = profitStatic > 0 && totalCap > 0 ? totalCap / profitStatic : Double.NaN;
        double peForward = profitAnnual > 0 && totalCap > 0 ? totalCap / profitAnnual : Double.NaN;
        return new RiskStore.Valuation(code, CodeName.name(code), date, close, totalCap, floatCap,
                pe, pb, profitTtm, equity, reportDate, peStatic, peForward, roeWeight);
    }

    private static double financeVal(double[] finance, int index) {
        return index >= 0 && index < finance.length ? finance[index] : Double.NaN;
    }

    /** 读某只票最新一根日线：{收盘价(元), 交易日}；库不存在或没数据返回 null */
    private static double[] lastBar(String full) {
        try {
            File dir = new File(MountConfig.currentDbDir());
            File file = new File(dir, full + ".db");
            if (!file.isFile()) {
                return null;
            }
            try (Connection conn = VaultDb.openCompat(file);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT TIME, CLOSE FROM T_D_Day ORDER BY TIME DESC LIMIT 1")) {
                if (!rs.next()) {
                    return null;
                }
                // 行情库里的 CLOSE 是「分」，145300 = 1453.00 元
                return new double[]{rs.getDouble(2) / 100.0, rs.getInt(1)};
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** code → [总股本, 流通A股]（股） */
    private static Map<String, double[]> loadShares() {
        Map<String, double[]> map = new HashMap<>();
        try (Connection conn = finance();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT code, report_date, total_shares, float_a_shares, free_float_shares"
                             + " FROM T_D_ShareCapital ORDER BY report_date DESC")) {
            while (rs.next()) {
                String code = CodeName.six(rs.getString(1));
                double total = rs.getDouble(3);
                double floatA = rs.getDouble(4);
                double free = rs.getDouble(5);
                if (total <= 0) {
                    continue;
                }
                double[] old = map.get(code);
                if (old == null) {
                    map.put(code, new double[]{total, floatA > 0 ? floatA : free});
                } else if (old[1] <= 0) {
                    old[1] = floatA > 0 ? floatA : free;
                }
            }
        } catch (Exception e) {
            return map;
        }
        return map;
    }

    /**
     * code → [归母净利TTM, 归母净资产, 总股本, 上年年报净利, 本期累计年化净利, 加权ROE, 报告期]
     *
     * <p>字段下标见 {@code FinanceFields}：95 归母净利、3 每股净资产、237 总股本、280 加权净资产收益率。
     * 只取「最新报告期 + 上年年报 + 上年同期」三期，别整表读 —— 一张表 14.6 万行 × 658 字段。</p>
     *
     * <p><b>ROE 别用字段 5</b>：那是「净利润 ÷ 期末归母净资产」的摊薄口径
     * （茅台 2026Q3 实测 23.77 = 644.7亿 ÷ 2712亿，一分不差）。加权口径在字段 280，
     * 它用加权平均净资产做分母，净资产增长的公司会明显更高（茅台 24.47、平安银行 8.07 对 7.04）。</p>
     */
    private static Map<String, double[]> loadFinance() {
        Map<String, double[]> result = new HashMap<>();
        try (Connection conn = finance()) {
            Set<Integer> periods = new LinkedHashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT DISTINCT report_date FROM T_D_Finance ORDER BY report_date DESC")) {
                int count = 0;
                while (rs.next() && count < 4) {
                    periods.add(rs.getInt(1));
                    count++;
                }
            }
            Set<Integer> wanted = new LinkedHashSet<>(periods);
            for (int period : periods) {
                wanted.add(yearAgo(period));
                wanted.add((period / 10000 - 1) * 10000 + 1231);
            }
            StringBuilder sql = new StringBuilder("SELECT code, report_date, fields FROM T_D_Finance"
                    + " WHERE report_date IN (");
            for (int i = 0; i < wanted.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
            }
            sql.append(")");
            Map<String, double[]> latest = new HashMap<>();
            Map<String, Map<Integer, double[]>> rows = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int index = 1;
                for (int period : wanted) {
                    ps.setInt(index++, period);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String code = CodeName.six(rs.getString(1));
                        int period = rs.getInt(2);
                        String csv = rs.getString(3);
                        if (csv == null) {
                            continue;
                        }
                        double[] picked = pick(csv, new int[]{95, 3, 237, 280});
                        rows.computeIfAbsent(code, k -> new HashMap<>()).put(period, picked);
                        double[] old = latest.get(code);
                        if (old == null || period > (int) old[4]) {
                            double[] copy = Arrays.copyOf(picked, 5);
                            copy[4] = period;
                            latest.put(code, copy);
                        }
                    }
                }
            }
            for (Map.Entry<String, double[]> entry : latest.entrySet()) {
                String code = entry.getKey();
                double[] now = entry.getValue();
                int period = (int) now[4];
                double cumProfit = now[0];
                double bps = now[1];
                double totalShares = now[2];
                double roeWeight = now[3];
                double ttm;
                double staticProfit;
                double annualProfit;
                if (period % 10000 == 1231) {
                    // 年报本身就是全年数，三种口径重合
                    ttm = cumProfit;
                    staticProfit = cumProfit;
                    annualProfit = cumProfit;
                } else {
                    Map<Integer, double[]> history = rows.getOrDefault(code, Map.of());
                    double[] prevYear = history.get(yearAgo(period));
                    double[] annual = history.get((period / 10000 - 1) * 10000 + 1231);
                    ttm = prevYear != null && annual != null
                            ? cumProfit + annual[0] - prevYear[0] : Double.NaN;
                    staticProfit = annual != null ? annual[0] : Double.NaN;
                    annualProfit = cumProfit * annualFactor(period);
                }
                double equity = bps > 0 && totalShares > 0 ? bps * totalShares : Double.NaN;
                result.put(code, new double[]{ttm, equity, totalShares, staticProfit, annualProfit,
                        roeWeight, period});
            }
        } catch (Exception e) {
            return result;
        }
        return result;
    }

    /**
     * 从 gpcw 的 fields 串里一次扫出指定的几个下标。
     *
     * <p>不用 {@code split(",")}：658 个字段 × 3 万多行会产生两千万个临时字符串，
     * 光 GC 就够界面卡一下；这里单趟扫描只留需要的几个 double。</p>
     */
    static double[] pick(String csv, int[] indexes) {
        int max = 0;
        for (int index : indexes) {
            max = Math.max(max, index);
        }
        double[] out = new double[indexes.length];
        Arrays.fill(out, Double.NaN);
        int field = 0;
        int start = 0;
        int length = csv.length();
        // 跳过开头的 '['，fields 是 "[1.23,4.56,...]" 这种
        if (length > 0 && csv.charAt(0) == '[') {
            start = 1;
        }
        for (int i = start; i <= length; i++) {
            if (i == length || csv.charAt(i) == ',') {
                for (int k = 0; k < indexes.length; k++) {
                    if (indexes[k] == field) {
                        out[k] = parse(csv, start, i);
                    }
                }
                field++;
                start = i + 1;
                if (field > max) {
                    break;
                }
            }
        }
        return out;
    }

    private static double parse(String text, int from, int to) {
        while (from < to && (text.charAt(from) == ' ' || text.charAt(from) == '[')) {
            from++;
        }
        while (to > from && (text.charAt(to - 1) == ' ' || text.charAt(to - 1) == ']')) {
            to--;
        }
        if (to <= from) {
            return Double.NaN;
        }
        try {
            double value = Double.parseDouble(text.substring(from, to));
            return Double.isNaN(value) || Double.isInfinite(value) ? Double.NaN : value;
        } catch (NumberFormatException e) {
            // 个别字段是 '' / 'nan' / 'None'
            return Double.NaN;
        }
    }

    private static Connection finance() throws Exception {
        return VaultDb.openCompat(FinanceDBManager.resolveDbFile(null));
    }

    private static int yearAgo(int date) {
        return (date / 10000 - 1) * 10000 + date % 10000;
    }

    /**
     * 把「本期累计净利」折成全年的系数：一季报 ×4、半年报 ×2、三季报 ×4/3、年报 ×1。
     *
     * <p>这就是国内软件说的「动态市盈率」—— 假定今年剩下的日子按目前节奏走。
     * 它和 TTM 的区别是：TTM 看的是<b>过去</b>四个季度，动态看的是<b>今年</b>的年化，
     * 所以在季节性强（比如白酒一季度占大头）的公司身上，两者能差出不少。</p>
     */
    static double annualFactor(int period) {
        switch (period % 10000) {
            case 331:
                return 4.0;
            case 630:
                return 2.0;
            case 930:
                return 4.0 / 3.0;
            default:
                return 1.0;
        }
    }

    /** 20261108 -> "2026-11-08" */
    static String iso(int date) {
        return String.format("%04d-%02d-%02d", date / 10000, date / 100 % 100, date % 100);
    }

    /** "2026-11-08 00:00:00" -> 20261108 */
    static int number(String date) {
        if (date == null) {
            return 0;
        }
        String digits = date.replace("-", "").replace(" ", "").replace(":", "");
        if (digits.length() < 8) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.substring(0, 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
