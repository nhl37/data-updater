package updater.holder;

import com.alibaba.fastjson2.JSONObject;

/**
 * 一条「十大流通股东」记录：某只股票、某个报告期、某一位股东。
 *
 * <p>原始数据来自东财数据中心 {@code RPT_DMSK_HOLDERS}（全市场按报告期批量取，
 * 每页 50 条），字段与本记录一一对应。其中 {@code marketCap} 是接口直接给的期末持股市值
 * （按报告期末收盘价算），不用自己乘价格 —— 这一点很关键：本地日线库不一定有复权后的
 * 季末价，自己算会和东财对不上。</p>
 *
 * <p>由一行数据可以反推出两个「免费」指标，省掉一次行情查询：</p>
 * <pre>
 *   隐含股价   price       = 持股市值 / 持股数
 *   流通股本   floatShares = 持股数 / 占流通股比例(%) × 100
 * </pre>
 */
public record HolderRow(
        int reportDate,
        String code,
        String stockName,
        int rank,
        String holderName,
        String holderKey,
        String holderType,
        String nature,
        HolderCategory category,
        long holdNum,
        double holdRatio,
        double marketCap,
        String direction,
        Double changeRatio,
        long holdNumChange) {

    /** 本期是「新进」（占流通股比例的变化没有可比的上期） */
    public boolean isNew() {
        return "新进".equals(direction);
    }

    public boolean isAdd() {
        return "增持".equals(direction);
    }

    public boolean isCut() {
        return "减持".equals(direction);
    }

    /** 隐含期末股价（元）；持股数为 0 时返回 0 */
    public double price() {
        return holdNum > 0 ? marketCap / holdNum : 0;
    }

    /** 由持股数与占流通股比例反推流通股本（股）；比例缺失时返回 0 */
    public double floatShares() {
        return holdRatio > 0 ? holdNum * 100.0 / holdRatio : 0;
    }

    /** 本期相对上期的增减股数：新进=全部持股，其余用接口给的变动数 */
    public long addedShares() {
        if (isNew()) {
            return holdNum;
        }
        return holdNumChange;
    }

    /** 本期新增持股市值（元）：可为负（减持） */
    public double addedCap() {
        return addedShares() * price();
    }

    /** 从接口 JSON 转成一行；字段缺失按 0 处理，不让一条脏数据毁掉整页 */
    public static HolderRow parse(int reportDate, JSONObject item) {
        String name = text(item.getString("HOLDER_NAME"));
        String newType = text(item.getString("HOLDER_NEWTYPE"));
        String nature = text(item.getString("HOLDER_NATURE"));
        return new HolderRow(
                reportDate,
                text(item.getString("SECURITY_CODE")),
                text(item.getString("SECURITY_NAME_ABBR")),
                item.getIntValue("RANK"),
                name,
                HolderStore.key(name),
                HolderCategory.typeLabel(newType, nature, name),
                nature,
                HolderCategory.classify(newType, nature, name),
                item.getLongValue("HOLD_NUM"),
                item.getDoubleValue("HOLD_RATIO"),
                item.getDoubleValue("HOLDER_MARKET_CAP"),
                text(item.getString("DIRECTION")),
                item.getDouble("HOLDNUM_CHANGE_RATIO"),
                item.getLongValue("HOLD_NUM_CHANGE"));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
