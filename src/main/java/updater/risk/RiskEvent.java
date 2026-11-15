package updater.risk;

/**
 * 一条风险事件。
 *
 * <p>只描述「哪只票、哪天、因为什么」——{@code title} 是**原始公告标题**，
 * 不做任何改写。界面上必须能原样看到这句话，用户自己判断严重程度；
 * 我们只负责把它归类、排序、标色。</p>
 *
 * @param code     6 位代码（与东财接口一致，不带 sh/sz 前缀）
 * @param name     股票简称，取不到时为 null
 * @param date     yyyyMMdd
 * @param category 类别
 * @param level    级别 3 重大 / 2 一般 / 1 提示（多数取自公告权重，解禁按占比算）
 * @param title    原始标题 / 类型说明
 * @param source   来源：ann 公告 / lift 解禁
 * @param ref      来源内唯一标识（公告 art_code / 解禁日期+类型），用于去重
 */
public record RiskEvent(String code, String name, int date, RiskCategory category, int level,
                        String title, String source, String ref) {

    /** 解禁按占流通比定级：≥10% 重大、≥3% 一般、其余提示 */
    public static int liftLevel(double freeRatio) {
        if (freeRatio >= 0.10) {
            return 3;
        }
        return freeRatio >= 0.03 ? 2 : 1;
    }
}
