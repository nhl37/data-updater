package updater.f10;

/**
 * 一条公告。
 *
 * @param artCode    东财公告编号（形如 AN202610301831246168），拼详情链接用
 * @param date       公告日期（yyyyMMdd，取 notice_date 的日期部分）
 * @param title      标题
 * @param columnName 公告类型（如「董事会决议公告」「持股变动公告」）
 * @param sentiment  利好 / 利空 / 中性，由 {@link AnnouncementJudge} 按标题关键词判定
 * @param weight     影响程度：3 = 重大，2 = 一般，0/1 = 不值得标到K线上
 * @param keyword    判定命中的词，写出来让人知道为什么这么判
 */
public record Announcement(String artCode, int date, String title, String columnName,
                           Sentiment sentiment, int weight, String keyword) {

    public enum Sentiment {
        /** 利好：红色 */
        GOOD(1, "利好"),
        /** 利空：绿色 */
        BAD(-1, "利空"),
        /** 中性：灰色，不标到K线上 */
        NEUTRAL(0, "中性");

        public final int code;
        public final String label;

        Sentiment(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static Sentiment of(int code) {
            for (Sentiment value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            return NEUTRAL;
        }
    }

    /** 是否值得在K线图上标出来：只有利好/利空且影响不小 */
    public boolean isMajor() {
        return sentiment != Sentiment.NEUTRAL && weight >= 2;
    }

    /**
     * 公告详情页
     */
    public String detailUrl(String stockCode) {
        String plain = F10Api.plain(stockCode);
        return "https://data.eastmoney.com/notices/detail/" + plain + "/" + artCode + ".html";
    }

    /** 日期文本：20261030 → 2026-10-30 */
    public String dateText() {
        int year = date / 10000;
        int month = date / 100 % 100;
        int day = date % 100;
        return String.format("%04d-%02d-%02d", year, month, day);
    }
}
