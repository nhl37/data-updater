package updater.f10;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 全市场公告「河流」：一次把最近几天所有 A 股的公告拉下来落库。
 *
 * <p>和 {@link AnnouncementRepository}（按股票拉 100 条）互补：那个解决「打开某只股票要看到完整历史」，
 * 这个解决「盘后更新完，全市场的公告都已经在本地」——一天全市场约 3000 条公告，
 * 100 条一页也就 30 页、几秒钟；换成逐只股票去问就是 5300 个请求。</p>
 *
 * <p>接口和个股公告是同一个，只是不带 {@code stock_list}，改成分页翻全部 A 股：</p>
 * <pre>
 *   np-anotice-stock.eastmoney.com/api/security/ann
 *     ?sr=-1&amp;page_size=100&amp;page_index=N&amp;ann_type=A&amp;client_source=web&amp;f_node=0&amp;s_node=0
 * </pre>
 * <p>返回每条的 {@code codes[]} 里带 {@code stock_code}，一条公告可能同时挂多只股票（如股东大会通知），
 * 这里每只都写一份——只有这样按股票查时不会漏。</p>
 */
public final class AnnouncementFeed {

    private static final String URL = "https://np-anotice-stock.eastmoney.com/api/security/ann"
            + "?sr=-1&page_size=%d&page_index=%d&ann_type=A&client_source=web&f_node=0&s_node=0";

    /** 每页条数：接口上限就是 100，写大了也只返回 100 */
    private static final int PAGE_SIZE = 100;

    /** 翻页上限：一天约 30 页，留足余量防止接口异常时无限翻 */
    private static final int MAX_PAGES = 400;

    /** 并发取多少页 */
    private static final int BATCH = 6;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private AnnouncementFeed() {
    }

    /** 单页失败重试次数：东财偶发读超时，重试一次基本就能过 */
    private static final int MAX_RETRY = 3;

    /** 同步结果 */
    public record Result(int pages, int rows, int codes, int days, String since, int failed) {
        public String text() {
            String text = "公告同步完成：翻 " + pages + " 页、" + rows + " 条，覆盖 " + codes
                    + " 只股票（回溯 " + days + " 天，至 " + since + "）";
            if (failed > 0) {
                // 缺页要说出来，否则「今天少了几百条」永远查不出原因
                text += "\n有 " + failed + " 页多次重试仍失败，这部分公告缺失，建议稍后再跑一次。";
            }
            return text;
        }
    }

    /**
     * 增量同步最近 {@code days} 天的全市场公告。
     *
     * <p>页码之间没有依赖，所以<b>一次并发取 {@value #BATCH} 页</b>：串行翻 3 天要 40 秒，
     * 并发后 10 秒左右。代价是遇到截止日时可能多取了几页，几 KB 的事。</p>
     *
     * @param days     回溯天数（1 = 只要今天；盘后跑 3~7 天足够补上周末与节假日）
     * @param progress 进度文字回调，可为 null
     */
    public static Result sync(int days, Consumer<String> progress) throws Exception {
        int back = Math.max(1, Math.min(days, 60));
        String cutoff = LocalDate.now().minusDays(back - 1L).format(DAY);
        Map<String, List<Announcement>> byCode = new LinkedHashMap<>();
        int pages = 0;
        int rows = 0;
        int failed = 0;
        Exception lastError = null;
        ExecutorService pool = Executors.newFixedThreadPool(BATCH);
        try {
            int next = 1;
            boolean done = false;
            while (!done && next <= MAX_PAGES) {
                List<Future<JSONArray>> futures = new ArrayList<>();
                for (int i = 0; i < BATCH && next + i <= MAX_PAGES; i++) {
                    final int page = next + i;
                    futures.add(pool.submit(() -> fetchPage(page)));
                }
                next += futures.size();
                for (Future<JSONArray> future : futures) {
                    JSONArray list;
                    try {
                        list = future.get();
                    } catch (Exception e) {
                        // 一页超时不能把整轮作废：已经翻到的页都在 byCode 里，
                        // 丢掉等于今天一条公告都没入库。记下来，继续翻下一页。
                        failed++;
                        lastError = e;
                        continue;
                    }
                    if (list == null || list.isEmpty()) {
                        done = true;
                        break;
                    }
                    pages++;
                    String oldest = null;
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject item = list.getJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String dateText = item.getString("notice_date");
                        int date = AnnouncementRepository.parseDate(dateText);
                        if (date <= 0) {
                            continue;
                        }
                        String day = dateText.substring(0, 10).replace("-", "");
                        if (oldest == null || day.compareTo(oldest) < 0) {
                            oldest = day;
                        }
                        if (day.compareTo(cutoff) < 0) {
                            // 更早的不要了；本页里够新的仍要处理
                            continue;
                        }
                        String artCode = item.getString("art_code");
                        String title = item.getString("title");
                        if (artCode == null || artCode.isEmpty() || title == null || title.isEmpty()) {
                            continue;
                        }
                        String column = firstColumn(item);
                        AnnouncementJudge.Judgment judgment = AnnouncementJudge.judge(title, column);
                        Announcement announcement = new Announcement(artCode, date, title, column,
                                judgment.sentiment(), judgment.weight(), judgment.keyword());
                        for (String code : stockCodes(item)) {
                            byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(announcement);
                            rows++;
                        }
                    }
                    if (progress != null) {
                        progress.accept("已翻 " + pages + " 页，累计 " + rows + " 条，当前到 " + oldest);
                    }
                    // 本页最老的一条都已经早于截止日，后面只会更早
                    if (oldest != null && oldest.compareTo(cutoff) < 0) {
                        done = true;
                        break;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 一页都没成功就是接口挂了（断网、被封），不能装作「今天没有公告」
        if (pages == 0 && lastError != null) {
            throw lastError;
        }
        F10Store.saveAnnouncementBatch(byCode);
        // 顺手把整表按当前规则重判一遍：老记录是旧规则写的，不重判会出现
        // 「界面上不标，SQL 查出来是重大利空」这种两套口径
        F10Store.rejudgeAll();
        F10Store.markAnnouncementFeed();
        return new Result(pages, rows, byCode.size(), back, cutoff, failed);
    }

    /**
     * 取一页，失败重试。东财这个接口大约每几十次会读超时一次，
     * 6 个线程并发翻 40 页时基本每轮都会碰上——不重试就整轮报废。
     */
    private static JSONArray fetchPage(int page) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return fetchPageOnce(page);
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRY) {
                    Thread.sleep(300L * attempt);
                }
            }
        }
        throw last;
    }

    private static JSONArray fetchPageOnce(int page) throws Exception {
        String body = F10Api.get(String.format(URL, PAGE_SIZE, page),
                "https://data.eastmoney.com/notices/");
        JSONObject root = JSONObject.parseObject(body);
        JSONObject data = root == null ? null : root.getJSONObject("data");
        return data == null ? null : data.getJSONArray("list");
    }

    /** 公告的分类（东财 columns[0].column_name），没有就空串 */
    private static String firstColumn(JSONObject item) {
        JSONArray columns = item.getJSONArray("columns");
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        JSONObject first = columns.getJSONObject(0);
        return first == null ? "" : String.valueOf(first.getString("column_name"));
    }

    /**
     * 这条公告挂在哪些 A 股上。
     *
     * <p>只看代码长得像不像股票：接口里混着可转债（11xxxx / 12xxxx）、基金（5xxxxx）、
     * 港股通（0xxxxx）等，它们进来会污染 {@code T_Announcement}——F10 按股票查是查不到，
     * 但按日期统计「今天有多少条重大公告」就会被它们撑大。</p>
     */
    private static List<String> stockCodes(JSONObject item) {
        List<String> codes = new ArrayList<>();
        JSONArray array = item.getJSONArray("codes");
        if (array == null) {
            return codes;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject one = array.getJSONObject(i);
            if (one == null) {
                continue;
            }
            String code = one.getString("stock_code");
            if (code == null || code.length() != 6 || !code.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (!looksLikeStock(code)) {
                continue;
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * 是否 A 股股票代码：沪 60/68、深 00/30、北交所 43/83/87/920。
     *
     * <p>可转债（11/12 开头）刻意排除——它们几乎天天有「转股价修正」「付息」这类公告，
     * 混进来会让每日公告量虚高一大截。</p>
     */
    static boolean looksLikeStock(String code) {
        if (code.startsWith("920")) {
            return true;
        }
        String prefix = code.substring(0, 2);
        switch (prefix) {
            case "60":
            case "68":
            case "00":
            case "30":
            case "43":
            case "83":
            case "87":
                return true;
            default:
                return false;
        }
    }
}
