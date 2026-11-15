package updater.finance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 某只股票、某个交易日、按当时<b>已经披露</b>的财报算出的一整套指标。
 *
 * <p>这是 {@code FIN("指标名")} 的数据来源。脚本里每写一个指标就是一次函数调用，
 * 而它们要的材料是同一份：这一天的收盘价、当期总股本、当期财报、上年年报、上年同期。
 * 所以这里把一整套一次算完，再按 {@code (股票, 交易日)} 记一份小缓存 ——
 * 一个脚本里写 6 个指标，真正取数只有 1 次，剩下 5 次是一次数组读。</p>
 *
 * <p><b>这就是不做「一个函数判断多个指标」的原因</b>：省下的只是重复取数，
 * 而重复取数在这里已经被缓存吃掉了；反过来，那样的函数得自己定义
 * 「指标名 运算符 阈值」的小语法，AND/OR/括号/REF/IF 全都用不上，
 * 也没法写「PE 比 PB 小」这种两个指标互相比较的条件。</p>
 *
 * <p><b>缺数据返回 NaN，不是 -99</b>：{@code FIN("PE_STATIC")<20} 是选股条件，
 * 如果没数据的股票返回 -99，它会永远小于 20 —— 把一堆没有财报的股票选进来，
 * 而且从结果上看不出来。NaN 参与比较恒为 false，正好是「不满足」的意思。
 * 代价是它不会被 {@code HHV/COUNT} 这类窗口函数当成空值跳过，那种用法要先自己判有效。</p>
 *
 * <p>口径与 {@code RiskSync.syncValuation} 完全一致（PE 静/动/TTM 三列、PB 用每股净资产、
 * ROE 用加权字段），区别只在于<b>按交易日取当时可见的那一期</b>，不是取最新一期。</p>
 */
public final class FinanceFigures {

    private static final double YI = 1e8;

    /** 什么都没有：所有指标 NaN */
    public static final FinanceFigures EMPTY = new FinanceFigures(0, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    /** 用的财报期 yyyyMMdd；0 表示这一天还没有财报可用 */
    private final int reportDate;
    /** 原始收盘价（元），已按当日除权因子折回 */
    private final double close;
    private final double totalShares;
    private final double floatShares;
    /** 总市值 / 流通市值（元） */
    private final double cap;
    private final double floatCap;
    /** 本期累计归母净利、TTM、上年年报、本期年化 */
    private final double cumProfit;
    private final double ttmProfit;
    private final double staticProfit;
    private final double annualizedProfit;
    /** 归母净资产（元）= 每股净资产 × 总股本 */
    private final double equity;

    private final double roe;
    private final double roeDiluted;
    private final double eps;
    private final double bps;
    private final double ocfPs;
    private final double revenue;
    private final double revenueYoy;
    private final double profitYoy;
    private final double grossMargin;
    private final double netMargin;
    private final double debtRatio;
    private final double holderCount;

    private FinanceFigures(int reportDate, double close, double totalShares, double floatShares,
                           double cap, double floatCap, double cumProfit, double ttmProfit,
                           double staticProfit, double annualizedProfit, double equity,
                           double roe, double roeDiluted, double eps, double bps, double ocfPs,
                           double revenue, double revenueYoy, double profitYoy,
                           double grossMargin, double netMargin, double debtRatio) {
        this.reportDate = reportDate;
        this.close = close;
        this.totalShares = totalShares;
        this.floatShares = floatShares;
        this.cap = cap;
        this.floatCap = floatCap;
        this.cumProfit = cumProfit;
        this.ttmProfit = ttmProfit;
        this.staticProfit = staticProfit;
        this.annualizedProfit = annualizedProfit;
        this.equity = equity;
        this.roe = roe;
        this.roeDiluted = roeDiluted;
        this.eps = eps;
        this.bps = bps;
        this.ocfPs = ocfPs;
        this.revenue = revenue;
        this.revenueYoy = revenueYoy;
        this.profitYoy = profitYoy;
        this.grossMargin = grossMargin;
        this.netMargin = netMargin;
        this.debtRatio = debtRatio;
        this.holderCount = Double.NaN;
    }

    private FinanceFigures(FinanceFigures source, double holderCount) {
        this.reportDate = source.reportDate;
        this.close = source.close;
        this.totalShares = source.totalShares;
        this.floatShares = source.floatShares;
        this.cap = source.cap;
        this.floatCap = source.floatCap;
        this.cumProfit = source.cumProfit;
        this.ttmProfit = source.ttmProfit;
        this.staticProfit = source.staticProfit;
        this.annualizedProfit = source.annualizedProfit;
        this.equity = source.equity;
        this.roe = source.roe;
        this.roeDiluted = source.roeDiluted;
        this.eps = source.eps;
        this.bps = source.bps;
        this.ocfPs = source.ocfPs;
        this.revenue = source.revenue;
        this.revenueYoy = source.revenueYoy;
        this.profitYoy = source.profitYoy;
        this.grossMargin = source.grossMargin;
        this.netMargin = source.netMargin;
        this.debtRatio = source.debtRatio;
        this.holderCount = holderCount;
    }

    public int reportDate() {
        return reportDate;
    }

    public double close() {
        return close;
    }

    /** 某个指标的值；没有数据返回 {@link Double#NaN} */
    public double value(FinanceMetric metric) {
        if (metric == null) {
            return Double.NaN;
        }
        switch (metric) {
            case PE_TTM:
                return multiple(cap, ttmProfit);
            case PE_STATIC:
                return multiple(cap, staticProfit);
            case PE_DYNAMIC:
                return multiple(cap, annualizedProfit);
            case PB:
                return equity > 0 ? multiple(cap, equity) : Double.NaN;
            case CAP:
                return yi(cap);
            case FLOAT_CAP:
                return yi(floatCap);
            case ROE:
                return roe;
            case ROE_DILUTED:
                return roeDiluted;
            case EPS:
                return eps;
            case BPS:
                return bps;
            case OCF_PS:
                return ocfPs;
            case REVENUE:
                return yi(revenue);
            case NET_PROFIT:
                return yi(cumProfit);
            case REVENUE_YOY:
                return revenueYoy;
            case PROFIT_YOY:
                return profitYoy;
            case GROSS_MARGIN:
                return grossMargin;
            case NET_MARGIN:
                return netMargin;
            case DEBT_RATIO:
                return debtRatio;
            case HOLDER_COUNT:
                return holderCount;
            case REPORT:
                return reportDate > 0 ? reportDate : Double.NaN;
            default:
                return Double.NaN;
        }
    }

    /** 这只股票这一天的全部材料（元/股口径），给因子库或界面复用 */
    public double cap() {
        return cap;
    }

    public double floatCap() {
        return floatCap;
    }

    public double ttmProfit() {
        return ttmProfit;
    }

    public double totalShares() {
        return totalShares;
    }

    public double floatShares() {
        return floatShares;
    }

    // ------------------------------------------------------------------ 取数

    /**
     * 某只股票某个交易日的指标快照（原始价口径：{@code closeYuan} 必须是原始价，不是复权价）。
     *
     * <p>同样的 {@code (股票, 交易日)} 在同一个线程里只算一次：脚本里 6 个指标、
     * 或者 {@code PE:=FIN("PE"); OUT:(PE<20) AND (PE>0)} 这种同一根K线上反复引用，
     * 都命中这份缓存。</p>
     */
    public static FinanceFigures of(String stockCode, int date, double closeYuan) {
        String code = stockCode == null ? "" : stockCode.trim();
        if (code.isEmpty() || date <= 0) {
            return EMPTY;
        }
        Entry cached = lookup(code, date, closeYuan);
        if (cached != null) {
            return cached.figures;
        }
        FinanceSnapshot.Timeline line = FinanceSnapshot.timeline(code);
        int index = line.latestIndexAsOf(date);
        double total = Double.NaN;
        double floatA = Double.NaN;
        if (index >= 0) {
            ShareCapitalRepository.Shares shares = ShareCapitalRepository.shares(code, date);
            total = shares.total;
            floatA = shares.floatA;
        }
        FinanceFigures figures = compute(line, index, date, closeYuan, total, floatA);
        store(code, date, closeYuan, figures);
        return figures;
    }

    /**
     * 真正算指标的地方：财报时间线 + 「当时可见的那一期」下标 + 当日股本。
     *
     * <p>抽出来是为了能造时间线做回归（{@code FinanceFiguresTest}），不用碰数据库。</p>
     */
    public static FinanceFigures compute(FinanceSnapshot.Timeline line, int index, int date,
                                         double closeYuan, double totalShareInput,
                                         double floatShareInput) {
        if (line == null || index < 0) {
            return EMPTY;
        }
        FinanceRecord row = line.row(index);
        if (row == null) {
            return EMPTY;
        }
        int reportDate = line.reportDate(index);

        // 股本：股本表优先（它有「哪一天起生效」的时间线），没有才退回财报里的两个字段
        double total = positive(totalShareInput) ? totalShareInput
                : row.value(FinanceFields.TOTAL_SHARES);
        double floatA = positive(floatShareInput) ? floatShareInput
                : row.value(FinanceFields.FLOAT_A_SHARES);
        if (!positive(floatA)) {
            floatA = total;
        }

        double close = closeYuan > 0 ? closeYuan : Double.NaN;
        double cap = positive(total) && !Double.isNaN(close) ? close * total : Double.NaN;
        double floatCap = positive(floatA) && !Double.isNaN(close) ? close * floatA : Double.NaN;

        double cumProfit = row.netProfitParent();
        double ttmProfit;
        double staticProfit;
        double annualizedProfit;
        if (reportDate > 0 && reportDate % 10000 == 1231) {
            // 年报本身就是全年数，三种 PE 口径重合
            ttmProfit = cumProfit;
            staticProfit = cumProfit;
            annualizedProfit = cumProfit;
        } else if (reportDate > 0) {
            int prevAnnualIndex = line.indexOfReport((reportDate / 10000 - 1) * 10000 + 1231);
            int prevSameIndex = line.indexOfReport(yearAgo(reportDate));
            double prevAnnual = visibleProfit(line, prevAnnualIndex, date);
            double prevSame = visibleProfit(line, prevSameIndex, date);
            ttmProfit = valid(cumProfit) && valid(prevAnnual) && valid(prevSame)
                    ? cumProfit + prevAnnual - prevSame : Double.NaN;
            staticProfit = prevAnnual;
            annualizedProfit = valid(cumProfit) ? cumProfit * annualFactor(reportDate) : Double.NaN;
        } else {
            ttmProfit = Double.NaN;
            staticProfit = Double.NaN;
            annualizedProfit = Double.NaN;
        }

        double bps = row.value(FinanceFields.NET_ASSET_PS);
        // 归母净资产用「每股净资产 × 总股本」：gpcw 的「所有者权益合计」含少数股东权益，
        // 拿它算 PB 会系统性偏低（口径与 RiskSync 一致）
        double equity = positive(bps) && positive(total) ? bps * total : Double.NaN;

        FinanceFigures figures = new FinanceFigures(reportDate, close, total, floatA, cap, floatCap,
                cumProfit, ttmProfit, staticProfit, annualizedProfit, equity,
                row.value(FinanceFields.ROE_WEIGHT), row.value(FinanceFields.ROE),
                row.value(FinanceFields.BASIC_EPS), bps, row.value(FinanceFields.OCF_PS),
                row.value(FinanceFields.OPERATING_REVENUE), row.value(FinanceFields.REVENUE_GROWTH),
                row.value(FinanceFields.PROFIT_GROWTH), row.value(FinanceFields.GROSS_MARGIN),
                row.value(FinanceFields.NET_MARGIN), row.value(FinanceFields.DEBT_RATIO));

        // 股东户数不在每期财报里都有，单独找「当时可见、且带这个字段」的最近一期
        int holderIndex = line.latestIndexWith(date, FinanceFields.HOLDER_COUNT);
        double holderCount = holderIndex < 0 ? Double.NaN : line.row(holderIndex).value(FinanceFields.HOLDER_COUNT);
        return new FinanceFigures(figures, holderCount);
    }

    /** 时间线里某一期的归母净利；这一期在当天还看不到（或不存在）时返回 NaN */
    private static double visibleProfit(FinanceSnapshot.Timeline line, int index, int date) {
        if (index < 0) {
            return Double.NaN;
        }
        int visible = line.visibleDate(index);
        if (visible <= 0) {
            visible = line.reportDate(index);
        }
        if (visible <= 0 || visible > date) {
            return Double.NaN;
        }
        FinanceRecord row = line.row(index);
        return row == null ? Double.NaN : row.netProfitParent();
    }

    /** 去年同期（同一个月日、年份减一）的报告期 */
    static int yearAgo(int reportDate) {
        return (reportDate / 10000 - 1) * 10000 + reportDate % 10000;
    }

    /** 把本期累计净利折成一年的系数：一季报 ×4、半年报 ×2、三季报 ×4/3、年报 ×1 */
    static double annualFactor(int reportDate) {
        switch (reportDate % 10000) {
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

    /** 倍数类：分母必须是正的 —— 亏损公司的 PE、负净资产的 PB 都没有意义，一律 NaN */
    private static double multiple(double numerator, double denominator) {
        if (!positive(numerator) || !positive(denominator)) {
            return Double.NaN;
        }
        return numerator / denominator;
    }

    private static double yi(double yuan) {
        return Double.isNaN(yuan) ? Double.NaN : yuan / YI;
    }

    private static boolean positive(double value) {
        return !Double.isNaN(value) && value > 0;
    }

    private static boolean valid(double value) {
        return !Double.isNaN(value);
    }

    // ------------------------------------------------------------ (股票,日期) 缓存

    /** 一个线程一份：脚本可能并行跑多只股票，锁在这里只会互相拖慢 */
    private static final int MAX_MEMO = 8;

    private static final ThreadLocal<Map<String, Entry>> MEMO =
            ThreadLocal.withInitial(() -> new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_MEMO;
                }
            });

    private static final class Entry {
        final int generation;
        final int date;
        final double close;
        final FinanceFigures figures;

        Entry(int generation, int date, double close, FinanceFigures figures) {
            this.generation = generation;
            this.date = date;
            this.close = close;
            this.figures = figures;
        }
    }

    /**
     * 缓存代次：{@link #invalidate()} 一加一，老条目自动作废。
     *
     * <p>不用「遍历各线程的 Map 清空」：缓存是每个线程一份，清不到别人的线程里；
     * 而且财报库或股本库更新后，同一只股票同一天的旧快照必须立刻失效，
     * 否则更新完数据再跑一遍，结果跟更新前一模一样。</p>
     */
    private static volatile int GENERATION;

    /** 财报/股本/除权数据更新后调用；扫描前也由 {@code ScanCaches} 调用 */
    public static void invalidate() {
        GENERATION++;
    }

    private static Entry lookup(String code, int date, double close) {
        Entry entry = MEMO.get().get(code);
        if (entry == null || entry.generation != GENERATION || entry.date != date) {
            return null;
        }
        // 同一天同一只票的收盘价是固定的，差一点点只可能是调用方传错（复权/未复权混用）
        return Math.abs(entry.close - close) < 1e-6 ? entry : null;
    }

    private static void store(String code, int date, double close, FinanceFigures figures) {
        MEMO.get().put(code, new Entry(GENERATION, date, close, figures));
    }
}
