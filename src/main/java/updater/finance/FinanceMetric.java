package updater.finance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本里能取的财务/估值指标：{@code FIN("名字")} 的一个名字对应这里的一项。
 *
 * <p>名字用全大写英文，是为了和函数库其它部分一致（也方便 AI 照抄），
 * 中文名只出现在提示与文档里。加指标只改这一处 + {@link FinanceFigures#value} +
 * 编辑器语法速查。</p>
 *
 * <p><b>金额类的单位是「亿元」</b>：选股写 {@code FIN("CAP")>300} 就是总市值 300 亿以上，
 * 不用数后面几个零。</p>
 */
public enum FinanceMetric {

    /** 滚动市盈率：总市值 ÷ 最近四个季度归母净利 */
    PE_TTM("PE_TTM", "滚动市盈率", "倍"),
    /** 静态市盈率：总市值 ÷ 上年年报归母净利 */
    PE_STATIC("PE_STATIC", "静态市盈率", "倍"),
    /** 动态市盈率：总市值 ÷（本期累计净利年化） */
    PE_DYNAMIC("PE_DYNAMIC", "动态市盈率", "倍"),

    PB("PB", "市净率", "倍"),

    /** 总市值（亿元）= 原始收盘价 × 当期总股本 */
    CAP("CAP", "总市值", "亿元"),
    /** 流通市值（亿元）= 原始收盘价 × 当期流通A股 */
    FLOAT_CAP("FLOAT_CAP", "流通市值", "亿元"),

    /** 加权净资产收益率（%），累计口径不年化 */
    ROE("ROE", "加权净资产收益率", "%"),
    /** 摊薄净资产收益率（%）：净利润 ÷ 期末归母净资产 */
    ROE_DILUTED("ROE_DILUTED", "摊薄净资产收益率", "%"),

    EPS("EPS", "基本每股收益", "元"),
    BPS("BPS", "每股净资产", "元"),
    OCF_PS("OCF_PS", "每股经营现金流", "元"),

    /** 营业收入（亿元），本期累计：三季报就是前九个月 */
    REVENUE("REVENUE", "营业收入", "亿元"),
    /** 归母净利润（亿元），本期累计 */
    NET_PROFIT("NET_PROFIT", "归母净利润", "亿元"),
    REVENUE_YOY("REVENUE_YOY", "营业收入同比", "%"),
    PROFIT_YOY("PROFIT_YOY", "净利润同比", "%"),

    GROSS_MARGIN("GROSS_MARGIN", "销售毛利率", "%"),
    NET_MARGIN("NET_MARGIN", "销售净利率", "%"),
    DEBT_RATIO("DEBT_RATIO", "资产负债率", "%"),

    /** 股东户数（户） */
    HOLDER_COUNT("HOLDER_COUNT", "股东户数", "户"),
    /** 当前用的财报期 yyyyMMdd，用来把「财报太旧」的股票筛掉 */
    REPORT("REPORT", "财报期", "yyyyMMdd");

    private final String key;
    private final String label;
    private final String unit;

    FinanceMetric(String key, String label, String unit) {
        this.key = key;
        this.label = label;
        this.unit = unit;
    }

    /** 脚本里写的名字 */
    public String key() {
        return key;
    }

    /** 中文名，报错与文档用 */
    public String label() {
        return label;
    }

    /** 单位，报错与文档用 */
    public String unit() {
        return unit;
    }

    private static final Map<String, FinanceMetric> BY_NAME = new LinkedHashMap<>();

    static {
        for (FinanceMetric metric : values()) {
            BY_NAME.put(metric.key, metric);
        }
        // 常见的几种写法：说 PE 默认指滚动口径，短后缀只是省几个字符，语义不另算一套
        BY_NAME.put("PE", PE_TTM);
        BY_NAME.put("PE_S", PE_STATIC);
        BY_NAME.put("PE_D", PE_DYNAMIC);
        BY_NAME.put("ROE_W", ROE);
        BY_NAME.put("ROE_WEIGHT", ROE);
        BY_NAME.put("MKT_CAP", CAP);
        BY_NAME.put("MARKET_CAP", CAP);
        BY_NAME.put("NET_PROFIT_PARENT", NET_PROFIT);
        BY_NAME.put("PROFIT", NET_PROFIT);
    }

    /** 按名字取指标；大小写、前后空格都容忍，不认识返回 null */
    public static FinanceMetric of(String name) {
        if (name == null) {
            return null;
        }
        return BY_NAME.get(name.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** 全部可用名字，拼报错信息用 */
    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (FinanceMetric metric : values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(metric.key);
        }
        return sb.toString();
    }

    /** 带中文名和单位的说明，AI 提示词与语法速查用 */
    public static String describe(FinanceMetric metric) {
        return metric.key + "（" + metric.label + "，" + metric.unit + "）";
    }
}
