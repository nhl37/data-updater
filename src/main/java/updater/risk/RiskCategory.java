package updater.risk;

import java.awt.Color;
import java.util.regex.Pattern;

/**
 * 风险事件的类别。
 *
 * <p>只有<b>事实</b>才进来：公告标题里明确写了「立案」「退市风险」「商誉减值」的，
 * 才归到这里；边猜边标的一律不算。判定口径和 K 线上那些三角同源（{@code AnnouncementJudge}），
 * 区别是这里要能按类别筛、按级别排，所以要多一层归类。</p>
 *
 * <p><b>级别</b>（{@link #level()}）：3 = 重大（可能影响能不能持有），
 * 2 = 一般（要盯，但不至于淘汰），1 = 提示（知道就行）。
 * 使用策略里默认「3 级直接淘汰、2 级扣分、1 级不动」，见 {@code RiskPolicy}。</p>
 *
 * <p><b>不要按类别名做价值判断</b>：这里的 label 只描述公告说了什么，
 * 不代表公司有问题。处罚可能已履行完毕，立案可能已撤销 —— 界面上必须能点回原始公告。</p>
 */
public enum RiskCategory {

    /** 立案调查 / 行政处罚 / 市场禁入 */
    INVESTIGATION("立案处罚", 3, new Color(0xCC, 0x00, 0x00),
            Pattern.compile("立案|(被)?调查|行政处罚|处罚决定|市场禁入")),

    /** 退市风险警示、终止上市 */
    DELIST("退市风险", 3, new Color(0xCC, 0x00, 0x00),
            Pattern.compile("退市风险|终止上市|暂停上市|面值退市")),

    /** 财务造假、虚假记载、违规担保、资金占用 */
    FRAUD("违规事项", 3, new Color(0xCC, 0x00, 0x00),
            Pattern.compile("违规担保|资金占用|非经营性占用|财务造假|虚假记载|信息披露违法")),

    /** 商誉/资产减值 */
    IMPAIR("计提减值", 3, new Color(0xCC, 0x55, 0x00),
            Pattern.compile("(大额|计提).*(减值|损失)|商誉减值|资产减值|信用减值")),

    /** 股权司法冻结、轮候冻结 */
    FREEZE("股权冻结", 3, new Color(0xCC, 0x00, 0x00),
            Pattern.compile("(股份|股权|资产).*(被)?(司法)?冻结|轮候冻结|司法拍卖")),

    /** 业绩预亏 / 预减 */
    LOSS("业绩预亏", 3, new Color(0xCC, 0x55, 0x00),
            Pattern.compile("业绩预亏|预计亏损|(首|续)亏|业绩预减|净利润.*(大幅|同比).*(下降|下滑)")),

    /** 交易所关注函 / 监管函 / 警示函 / 纪律处分 */
    REGULATOR("监管函件", 2, new Color(0x99, 0x77, 0x00),
            Pattern.compile("关注函|监管函|警示函|监管工作函|纪律处分|通报批评")),

    /** 诉讼、仲裁 */
    LITIGATION("诉讼仲裁", 2, new Color(0x99, 0x77, 0x00),
            Pattern.compile("诉讼|仲裁")),

    /** 股东质押的平仓风险（不含常规质押，那个一年十几次） */
    PLEDGE("质押平仓", 2, new Color(0x99, 0x77, 0x00),
            Pattern.compile("补充质押|触及平仓|平仓风险|质押.*违约|被动减持")),

    /** 债务逾期、违约 */
    DEFAULT("逾期违约", 2, new Color(0x99, 0x77, 0x00),
            Pattern.compile("逾期|违约")),

    /** 限售解禁（来自解禁表，不来自公告） */
    LIFT("限售解禁", 1, new Color(0x77, 0x77, 0x77),
            Pattern.compile("限售|解禁")),

    /** 大股东/董监高减持 */
    REDUCE("股东减持", 1, new Color(0x77, 0x77, 0x77),
            Pattern.compile("减持")),

    /** 兜底 */
    OTHER("其他利空", 1, new Color(0x77, 0x77, 0x77), Pattern.compile("."));

    private final String label;
    private final int level;
    private final Color color;
    private final Pattern pattern;

    RiskCategory(String label, int level, Color color, Pattern pattern) {
        this.label = label;
        this.level = level;
        this.color = color;
        this.pattern = pattern;
    }

    public String label() {
        return label;
    }

    public int level() {
        return level;
    }

    public Color color() {
        return color;
    }

    /** 库里存的是 name()，改顺序不影响老库；但**不要改名字** */
    public static RiskCategory of(String name) {
        for (RiskCategory category : values()) {
            if (category.name().equals(name)) {
                return category;
            }
        }
        return OTHER;
    }

    /**
     * 按公告标题归类。
     *
     * <p>顺序有讲究：先判「立案调查」再判「调查」，否则「关于被立案调查的公告」会被兜底吃掉；
     * 减持放最后，因为它太常见，前面任何一条命中都不该被它抢走。</p>
     */
    public static RiskCategory classify(String title, String keyword) {
        String text = title == null ? "" : title;
        for (RiskCategory category : new RiskCategory[]{INVESTIGATION, DELIST, FRAUD, FREEZE, IMPAIR,
                LOSS, REGULATOR, LITIGATION, PLEDGE, DEFAULT, REDUCE}) {
            if (category.pattern.matcher(text).find()) {
                return category;
            }
        }
        return OTHER;
    }

    /** 3 级 = 重大 */
    public boolean major() {
        return level >= 3;
    }
}
