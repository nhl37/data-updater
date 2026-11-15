package updater.f10;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按公告标题判断利好/利空。
 *
 * <p>东财的公告接口只给类型（董事会决议、持股变动…），没有情绪字段，只能按标题关键词判。
 * 这条规则不是「预测涨跌」，只是把「明显的好消息/坏消息」挑出来标在K线上，
 * 所以宁缺勿滥：判不准的一律当中性，不标。</p>
 *
 * <p>判定顺序（先命中先返回）：</p>
 * <ol>
 *   <li><b>修正规则</b>：「终止减持」「不减持」「增持计划完成」这类字面像利空、实际是利好的，先捞出来</li>
 *   <li><b>重大</b>（weight 3）：立案、退市、预亏、大额减值、重大合同、预增、回购、重组、获批</li>
 *   <li><b>一般</b>（weight 2）：减持、质押、诉讼、问询、终止重组；分红、合作、订单、专利、补助</li>
 *   <li><b>无关</b>（weight 0）：会议决议、章程制度、聘任辞职、摘要更正——这类占了公告的大多数，
 *       必须挡在前面，否则「董事会决议公告」会被后面的「决议」之类关键词带进利好/利空</li>
 * </ol>
 */
public final class AnnouncementJudge {

    /** 一条判定规则 */
    private record Rule(Pattern pattern, Announcement.Sentiment sentiment, int weight, String label) {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    /** 1. 修正：字面像利空、实际是利好（或反过来） */
    private static final List<Rule> CORRECTIONS = List.of(
            new Rule(p("(提前)?终止减持|不减持|减持计划.*(届满|完成).*(未减持|未实施)"),
                    Announcement.Sentiment.GOOD, 2, "终止减持"),
            new Rule(p("终止.*(回购|增持)"),
                    Announcement.Sentiment.BAD, 2, "终止回购/增持"),
            new Rule(p("(撤销|撤回).*(退市|立案)"),
                    Announcement.Sentiment.GOOD, 2, "撤销退市/立案"),
            // 「非经营性资金占用及其他关联资金往来情况的专项说明/汇总表」是年报、半年报的标准附件
            // （审计机构出具），每期都发，不代表公司真占用了资金。字面命中「资金占用」，
            // 必须先改判成中性，否则每只股票一年白得两个利空三角。
            new Rule(p("专项(说明|审核|审计|鉴证|报告)|鉴证报告|(资金占用|关联资金往来).*(汇总表|情况表|专项|情况)"),
                    Announcement.Sentiment.NEUTRAL, 0, "专项说明/鉴证"),
            // 新股上市前披露的发行文件：「发行人及中介机构关于问询函的回复」字面命中「问询函」，
            // 会被当成交易所监管函判利空，其实是IPO审核流程的一部分，与上市后股价无关。
            new Rule(p("发行人|中介机构|预先审阅|招股(说明书|意向书)|上市公告书|发行保荐书|首发(上市|公告)"),
                    Announcement.Sentiment.NEUTRAL, 0, "发行文件"),
            // 并购重组的「审核问询函」及会计师/评估师的回复，是重组流程的必经文件，
            // 字面命中「问询函」被当成交易所监管函判利空——这类一次能连发五六份
            new Rule(p("审核问询函|问询函(的)?回复|回复.*问询函|重组.*问询|并购.*问询"),
                    Announcement.Sentiment.NEUTRAL, 0, "重组问询/回复"),
            // 可转债转股价下修是转债投资者的事，对正股只是轻微摊薄；
            // 「不向下修正」更是惯例表态，一天能冒出十几只
            new Rule(p("(不)?(向下)?修正.*转股价格|转股价格.*(向下)?修正|转股价.*修正"),
                    Announcement.Sentiment.NEUTRAL, 0, "转股价修正"),
            // 股票交易异常波动公告没有新信息（多半是「公司也不知情」），
            // 但它天天出现在涨幅榜的票上，判利空等于每根大阳线配一个绿三角
            new Rule(p("(股票)?交易(价格)?异常波动|交易风险提示"),
                    Announcement.Sentiment.NEUTRAL, 0, "异常波动"),
            // 既解除又再质押：净中性，不能按「解除=利好」也不该按「质押=利空」
            new Rule(p("解除质押.*(再|重新|继续)质押"),
                    Announcement.Sentiment.NEUTRAL, 0, "解押再质押"),
            // 解除质押本身是降风险，但属于常规操作，只在列表里体现（weight 1）
            new Rule(p("解除质押|质押(融资)?到期.*(还款|解除)|质押.*购回"),
                    Announcement.Sentiment.GOOD, 1, "解除质押"));

    /**
     * 2. 例行进展：同一件事的重复披露，字面像利好其实已经涨过了。
     *
     * <p>最典型的是回购：「回购股份实施进展」每月一条，「前十大股东持股情况」「通知债权人」
     * 只是配套文件。全按重大标，一只股票一年多出 20 个红三角，K线就没法看了。
     * 这里降到 weight 1（不进K线，只在F10列表里留个痕迹）。</p>
     */
    private static final List<Rule> ROUTINE = List.of(
            // 「前十名股东」和「前十大股东」都是同一份配套文件，两种写法都得挡
            new Rule(p("回购.*(进展|前十(名|大)|债权人|价格上限|调整.*上限)"),
                    Announcement.Sentiment.GOOD, 1, "回购例行公告"),
            new Rule(p("增持.*(进展|贷款|承诺函)"),
                    Announcement.Sentiment.GOOD, 1, "增持例行公告"),
            // 员工持股/股权激励一次方案会连发 5~6 份文件（草案、摘要、管理办法、合规性说明、
            // 核查意见、法律意见书），同一件事重复披露。全按一般的标，同一天堆 6 个三角。
            // 方案类降到 weight 1，只留「完成/过户/授予」这类实质进展进K线。
            new Rule(p("(员工持股|股权激励).*(草案|摘要|办法|合规性说明|核查意见|法律意见书)"),
                    Announcement.Sentiment.GOOD, 1, "激励方案文件"),
            new Rule(p("减持.*(进展|时间过半|实施情况|权益变动)"),
                    Announcement.Sentiment.BAD, 1, "减持例行公告"),
            // 「权益分派实施公告」是除权除息日程通知，那天股价必然跳空低开，
            // 标成红三角反而像是利好兑现。真正有意义的是更早的「分配方案/预案」。
            new Rule(p("(权益分派|分红|利润分配|转增).*(实施|除权除息|每股(派发|现金))"),
                    Announcement.Sentiment.GOOD, 1, "分红实施"),
            // 质押公告一年能发十几次（每次股东押一点就发一条），全标到K线上满屏绿三角；
            // 真正危险的是「补充质押」（股价跌到快平仓）和「触及平仓线」，只有它们进K线
            new Rule(p("质押"),
                    Announcement.Sentiment.BAD, 1, "股东质押"));

    /**
     * 2.5 程序性文件：律师、保荐机构、会计师出的见证/核查意见。
     *
     * <p>这类文件本身不含新信息，只是给前面那条真公告配的「法律手续齐备」证明。
     * 跟着回购走就被判成利好、跟着股东大会走就无关，按内容判不了，
     * 一律中性——否则光一个股东大会就能刷出两三条红字。</p>
     */
    private static final List<Rule> PROCEDURAL = List.of(
            new Rule(p("法律意见书|核查意见|合规性说明|鉴证报告|保荐(机构)?(意见|报告)|持续督导"),
                    Announcement.Sentiment.NEUTRAL, 0, "程序性文件"),
            // 「业绩说明会」「投资者交流活动记录表」这类，标题里常带「现金分红」「业绩预增」
            // 这些诱人的词，按正文关键词判会一律变成利好；实际只是与投资者沟通的日程与记录
            new Rule(p("说明会|投资者关系|调研|交流活动|记录表"),
                    Announcement.Sentiment.NEUTRAL, 0, "交流活动"));

    /** 3. 重大利好 */
    private static final List<Rule> MAJOR_GOOD = List.of(
            new Rule(p("业绩预增|净利润.*(大幅|同比).*(增长|上升)|扭亏为盈|预计盈利"),
                    Announcement.Sentiment.GOOD, 3, "业绩预增"),
            new Rule(p("重大合同|中标|中选|(签订|签署).*(重大|亿元).*(合同|协议)"),
                    Announcement.Sentiment.GOOD, 3, "重大合同/中标"),
            new Rule(p("回购"),
                    Announcement.Sentiment.GOOD, 3, "回购"),
            new Rule(p("增持"),
                    Announcement.Sentiment.GOOD, 3, "增持"),
            new Rule(p("重组|资产注入|资产置换|控制权.*(变更|转让)|(国资|国有).*入主|要约收购"),
                    Announcement.Sentiment.GOOD, 3, "重组/控制权变更"),
            new Rule(p("(注册批件|生产批件|获批|批准上市|临床).*(获批|批准|受理)|获得.*(批件|证书)"),
                    Announcement.Sentiment.GOOD, 3, "获批"),
            new Rule(p("涨价|提价|上调.*价格"),
                    Announcement.Sentiment.GOOD, 3, "涨价"));

    /** 4. 重大利空 */
    private static final List<Rule> MAJOR_BAD = List.of(
            new Rule(p("立案|(被)?调查|(行政处罚|处罚决定|市场禁入)"),
                    Announcement.Sentiment.BAD, 3, "立案/处罚"),
            new Rule(p("退市风险|终止上市|暂停上市|面值退市"),
                    Announcement.Sentiment.BAD, 3, "退市风险"),
            new Rule(p("业绩预亏|预计亏损|(首|续)亏|净利润.*(大幅|同比).*(下降|下滑)|业绩预减"),
                    Announcement.Sentiment.BAD, 3, "业绩预亏"),
            new Rule(p("(大额|计提).*(减值|损失)|商誉减值|资产减值"),
                    Announcement.Sentiment.BAD, 3, "计提减值"),
            new Rule(p("(股份|股权|资产).*(被)?(司法)?冻结|轮候冻结"),
                    Announcement.Sentiment.BAD, 3, "股权冻结"),
            new Rule(p("违规担保|资金占用|非经营性占用|财务造假|虚假记载"),
                    Announcement.Sentiment.BAD, 3, "违规事项"));

    /** 5. 一般利空 */
    private static final List<Rule> MINOR_BAD = List.of(
            new Rule(p("减持"), Announcement.Sentiment.BAD, 2, "减持"),
            new Rule(p("补充质押|触及平仓|平仓风险|质押.*违约|被动减持"),
                    Announcement.Sentiment.BAD, 2, "补充质押/平仓风险"),
            new Rule(p("诉讼|仲裁"), Announcement.Sentiment.BAD, 2, "诉讼/仲裁"),
            // 「问询函」不进这里：并购重组/IPO 的审核问询函占绝大多数，已在 CORRECTIONS 里挡掉，
            // 交易所对已上市公司的监管问询通常写成「关注函/监管工作函/警示函」
            new Rule(p("关注函|监管函|警示函|监管工作函|纪律处分"), Announcement.Sentiment.BAD, 2, "交易所函件"),
            new Rule(p("(终止|中止|撤回).*(重组|发行|定增|收购|上市)"), Announcement.Sentiment.BAD, 2, "终止重大事项"),
            new Rule(p("下修|向下修正"), Announcement.Sentiment.BAD, 2, "下修"),
            new Rule(p("逾期|违约"), Announcement.Sentiment.BAD, 2, "逾期/违约"),
            new Rule(p("风险提示"), Announcement.Sentiment.BAD, 2, "风险提示"));

    /** 6. 一般利好 */
    private static final List<Rule> MINOR_GOOD = List.of(
            new Rule(p("(年度|半年度|季度|中期)?(权益分派|分红|利润分配)"), Announcement.Sentiment.GOOD, 2, "分红"),
            new Rule(p("(资本公积|盈余公积).*(转增|送股)|送转"), Announcement.Sentiment.GOOD, 2, "送转"),
            new Rule(p("战略合作|框架协议|合作协议|(签订|签署).*(协议|合同|意向)"),
                    Announcement.Sentiment.GOOD, 2, "合作/订单"),
            new Rule(p("投产|量产|试生产|达产"), Announcement.Sentiment.GOOD, 2, "投产/量产"),
            new Rule(p("专利|(获得|取得).*(证书|资质|许可)"), Announcement.Sentiment.GOOD, 2, "专利/资质"),
            new Rule(p("政府补助|财政补贴|税收优惠|退税"), Announcement.Sentiment.GOOD, 2, "补助/优惠"),
            new Rule(p("股权激励|员工持股"), Announcement.Sentiment.GOOD, 2, "股权激励"));

    /** 7. 与股价基本无关的公告：会议、制度、人事、摘要更正 */
    private static final List<Rule> NEUTRAL = List.of(
            new Rule(p("董事会|监事会|股东大会|(会议|决议)|章程|议事规则|管理制度|内部控制"),
                    Announcement.Sentiment.NEUTRAL, 0, "会议/制度"),
            new Rule(p("(聘任|解聘|辞职|换届|选举|补选)"), Announcement.Sentiment.NEUTRAL, 0, "人事"),
            new Rule(p("摘要|英文版|更正|补充公告|(提示性)?公告的(更正|补充)"),
                    Announcement.Sentiment.NEUTRAL, 0, "摘要/更正"),
            new Rule(p("业绩说明会|投资者关系|调研"), Announcement.Sentiment.NEUTRAL, 0, "交流活动"),
            new Rule(p("(工商|注册地址|注册资本|经营范围).*(变更|登记)|营业执照"),
                    Announcement.Sentiment.NEUTRAL, 0, "工商变更"));

    private AnnouncementJudge() {
    }

    /**
     * 判定一条公告。
     *
     * @return 长度为 3 的数组：{情绪码 1/0/-1, 权重 0~3, 命中词}
     */
    public static Judgment judge(String title, String columnName) {
        String text = title == null ? "" : title;
        // 标题里前半段是「股票简称:」，后面才是正文，整串匹配即可
        for (List<Rule> group : List.of(CORRECTIONS, ROUTINE, PROCEDURAL, MAJOR_GOOD, MAJOR_BAD,
                MINOR_BAD, MINOR_GOOD, NEUTRAL)) {
            Judgment hit = match(group, text);
            if (hit != null) {
                return hit;
            }
        }
        // 兜底：不算利好也不算利空，但保留在列表里（用户自己看标题）
        return new Judgment(Announcement.Sentiment.NEUTRAL, 1, columnName == null ? "" : columnName);
    }

    private static Judgment match(List<Rule> rules, String text) {
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                return new Judgment(rule.sentiment(), rule.weight(), rule.label());
            }
        }
        return null;
    }

    /** 判定结果 */
    public record Judgment(Announcement.Sentiment sentiment, int weight, String keyword) {
    }

    /**
     * 只保留能标到K线上的（利好/利空且 weight ≥ 2）
     */
    public static List<Announcement> majors(List<Announcement> all) {
        List<Announcement> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        for (Announcement item : all) {
            if (item.isMajor()) {
                result.add(item);
            }
        }
        return result;
    }
}
