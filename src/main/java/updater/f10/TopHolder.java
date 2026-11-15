package updater.f10;

/**
 * 十大流通股东里的一位，附带与上一报告期的对比。
 *
 * @param rank      排名 1~10
 * @param name      股东名称
 * @param type      股东类型（东财 HOLDER_NEWTYPE：基金、社保、QFII、个人…）
 * @param holdNum   本期持股数（股）
 * @param freeRatio 占流通股比例（%）
 * @param changeNum 较上期增减股数：正=增持，负=减持，0=不变
 * @param changePct 较上期增减幅度（%）：上期没有时为空（新进）
 * @param kind      变动类型
 */
public record TopHolder(int rank, String name, String type, long holdNum, double freeRatio,
                        long changeNum, Double changePct, Kind kind) {

    /** 与上期相比的变动类型 */
    public enum Kind {
        NEW("新进"),
        ADD("增持"),
        CUT("减持"),
        HOLD("不变"),
        EXIT("退出");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * 变动文本：+1234.5 万股（+23.1%）／ 新进 ／ 不变
     */
    public String changeText() {
        if (kind == Kind.NEW) {
            return "新进";
        }
        if (kind == Kind.HOLD) {
            return "不变";
        }
        String amount = (changeNum > 0 ? "+" : "-") + formatShares(Math.abs(changeNum));
        if (changePct == null) {
            return amount;
        }
        // 小幅变动按一位小数会全变成「+0.0%」，看起来像没动却标着红字，改成按量级选精度
        String pct = Math.abs(changePct) < 0.1
                ? String.format("%+.3f%%", changePct)
                : String.format("%+.1f%%", changePct);
        return amount + "（" + pct + "）";
    }

    /**
     * 股数转成「万/亿股」，表格里不出现一长串数字
     */
    public static String formatShares(long shares) {
        // 不足 1 万股就按股显示：否则「减持 3300 股」会写成「-0 万」，看起来像没动
        if (Math.abs(shares) < 10000) {
            return shares + " 股";
        }
        double value = shares / 10000.0;
        if (Math.abs(value) >= 10000) {
            return String.format("%.2f 亿", value / 10000);
        }
        return String.format("%.0f 万", value);
    }
}
