package updater.finance;

/**
 * 财报库（gpcw）字段下标定义。
 * <p>
 * 财报库里 T_D_Finance 的 fields 列是一串浮点数（gpcw .dat 每条记录的原始字段），
 * 顺序与获取环境 mootdx/financial/columns.py 一致：columns[0] 是 report_date，
 * fields[0] 对应 columns[1]“基本每股收益”，即 <b>fields 下标 = columns 下标 - 1</b>。
 * <p>
 * 只保留财报画面与列表评价用得到的字段，其余字段不解析。
 */
public final class FinanceFields {

    /** 基本每股收益（元） */
    public static final int BASIC_EPS = 0;
    /** 扣除非经常性损益每股收益（元） */
    public static final int DEDUCTED_EPS = 1;
    /** 每股未分配利润（元） */
    public static final int UNDISTRIBUTED_PROFIT_PS = 2;
    /** 每股净资产（元） */
    public static final int NET_ASSET_PS = 3;
    /** 每股资本公积金（元） */
    public static final int CAPITAL_RESERVE_PS = 4;
    /** 净资产收益率（%）—— <b>摊薄</b>口径：净利润 ÷ 期末归母净资产 */
    public static final int ROE = 5;
    /**
     * 加权净资产收益率（%），columns.py 第 283 项「加权净资产收益率(每股指标)」。
     *
     * <p>和上面 {@link #ROE} 不是一回事：加权用<b>加权平均净资产</b>做分母，
     * 净资产在增长的公司会明显更高。实测茅台 2026Q3 摊薄 23.77 / 加权 24.47，
     * 平安银行 7.04 / 8.07。券商软件里的「ROE(加权)」要的是这一个。</p>
     *
     * <p>口径是<b>年初至今累计、不年化</b>：三季报给的就是前九个月的数，
     * 看它得同报告期横向比，别拿 Q3 的 8% 去比年报的 12%。</p>
     */
    public static final int ROE_WEIGHT = 280;
    /** 每股经营现金流量（元） */
    public static final int OCF_PS = 6;

    /** 资产总计（元） */
    public static final int TOTAL_ASSETS = 39;
    /** 负债合计（元） */
    public static final int TOTAL_LIABILITIES = 62;
    /** 所有者权益合计（元） */
    public static final int OWNER_EQUITY = 71;
    /** 营业收入（元） */
    public static final int OPERATING_REVENUE = 73;
    /** 营业成本（元） */
    public static final int OPERATING_COST = 74;
    /** 营业利润（元） */
    public static final int OPERATING_PROFIT = 85;
    /** 利润总额（元） */
    public static final int TOTAL_PROFIT = 91;
    /** 净利润（元） */
    public static final int NET_PROFIT = 94;
    /** 归属于母公司所有者的净利润（元） */
    public static final int NET_PROFIT_PARENT = 95;
    /** 经营活动产生的现金流量净额（元） */
    public static final int OPERATING_CASH_FLOW = 106;
    /** 投资活动产生的现金流量净额（元） */
    public static final int INVESTING_CASH_FLOW = 118;

    /** 营业收入增长率（%） */
    public static final int REVENUE_GROWTH = 182;
    /** 净利润增长率（%） */
    public static final int PROFIT_GROWTH = 183;
    /** 净资产增长率（%） */
    public static final int EQUITY_GROWTH = 184;
    /** 销售净利率（%） */
    public static final int NET_MARGIN = 198;
    /** 销售毛利率（%） */
    public static final int GROSS_MARGIN = 201;
    /** 资产负债率（%） */
    public static final int DEBT_RATIO = 209;

    /** 总股本（股） */
    public static final int TOTAL_SHARES = 237;
    /** 已上市流通A股（股）—— 换手率的分母 */
    public static final int FLOAT_A_SHARES = 238;
    /** 股东人数（户） */
    public static final int HOLDER_COUNT = 241;
    /** 第一大股东的持股数量（股） */
    public static final int TOP1_HOLDER_SHARES = 242;
    /** 十大流通股东持股数量合计（股） */
    public static final int TOP10_FREE_HOLDER_SHARES = 243;

    /** 解析 fields 时需要的最大下标，再往后的字段不展示 */
    public static final int MAX_INDEX = ROE_WEIGHT;

    private FinanceFields() {
    }
}
