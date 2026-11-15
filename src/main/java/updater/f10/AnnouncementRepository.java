package updater.f10;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 公告数据：优先读本地库，过期或没有才联网拉。
 *
 * <p>接口是东财公告页用的那个（{@code np-anotice-stock.eastmoney.com/api/security/ann}），
 * 一次拉 100 条按日期倒序，足够覆盖最近半年——绝大多数股票半年内公告不到 100 条。
 * 拉回来先按 {@link AnnouncementJudge} 打上利好/利空标签，再整批落库，
 * 这样K线图要「哪天有重大公告」时直接读本地，不用再联网。</p>
 */
public final class AnnouncementRepository {

    private static final String URL = "https://np-anotice-stock.eastmoney.com/api/security/ann"
            + "?sr=-1&page_size=%d&page_index=1&ann_type=A&client_source=web&stock_list=%s";

    /** 一次取多少条 */
    private static final int PAGE_SIZE = 100;

    private AnnouncementRepository() {
    }

    /**
     * 取公告（本地优先，必要时联网）。
     *
     * @param code    股票代码，sh600000 / 600000 都行
     * @param refresh true 表示忽略缓存直接联网
     * @throws Exception 联网失败且本地也没有任何记录时抛出，界面显示「加载失败」
     */
    public static List<Announcement> load(String code, boolean refresh) throws Exception {
        String plain = F10Api.plain(code);
        if (plain.isEmpty()) {
            return new ArrayList<>();
        }
        long fetchedAt = F10Store.announcementFetchedAt(plain);
        boolean fresh = fetchedAt > 0
                && System.currentTimeMillis() - fetchedAt < F10Store.TTL_ANNOUNCEMENT;
        if (!refresh && fresh) {
            List<Announcement> cached = cached(plain);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        try {
            List<Announcement> items = fetch(plain);
            F10Store.saveAnnouncements(plain, items);
            F10Store.markAnnouncementsFetched(plain);
            return items;
        } catch (Exception e) {
            // 联网失败：有旧数据就拿旧的，界面照常显示，只是可能少几条最新公告
            List<Announcement> cached = cached(plain);
            if (!cached.isEmpty()) {
                return cached;
            }
            throw e;
        }
    }

    /**
     * 读本地公告，并用<b>当前</b>的判断规则重新打一次标签。
     *
     * <p>库里的 SENTIMENT/WEIGHT 是获取那一刻的判定快照，规则一改（比如把「回购进展」
     * 从重大降为例行）旧记录就固化了旧结论，而公告本身是不会变的。所以每次读出来都重判一遍，
     * 规则迭代立即生效，不用清库重拉。</p>
     */
    private static List<Announcement> cached(String plain) throws Exception {
        List<Announcement> stored = F10Store.loadAnnouncements(plain, PAGE_SIZE);
        List<Announcement> result = new ArrayList<>(stored.size());
        for (Announcement item : stored) {
            AnnouncementJudge.Judgment judgment =
                    AnnouncementJudge.judge(item.title(), item.columnName());
            result.add(new Announcement(item.artCode(), item.date(), item.title(),
                    item.columnName(), judgment.sentiment(), judgment.weight(),
                    judgment.keyword()));
        }
        return result;
    }

    /**
     * 只要「重大」公告（利好/利空且 weight ≥ 2），按日期升序，供K线标记使用。
     * 不会抛异常：拉不到就返回本地已有的；本地也没有就返回空列表。
     */
    public static List<Announcement> loadMajors(String code) {
        try {
            List<Announcement> all = load(code, false);
            List<Announcement> majors = AnnouncementJudge.majors(all);
            majors.sort((a, b) -> Integer.compare(a.date(), b.date()));
            return majors;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<Announcement> fetch(String plain) throws Exception {
        String url = String.format(URL, PAGE_SIZE, plain);
        String body = F10Api.get(url, "https://data.eastmoney.com/notices/stock/" + plain + ".html");
        JSONObject root = JSONObject.parseObject(body);
        JSONObject data = root == null ? null : root.getJSONObject("data");
        JSONArray list = data == null ? null : data.getJSONArray("list");
        List<Announcement> items = new ArrayList<>();
        if (list == null) {
            return items;
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String artCode = item.getString("art_code");
            String title = item.getString("title");
            int date = parseDate(item.getString("notice_date"));
            String column = "";
            JSONArray columns = item.getJSONArray("columns");
            if (columns != null && !columns.isEmpty()) {
                JSONObject first = columns.getJSONObject(0);
                if (first != null) {
                    column = first.getString("column_name");
                }
            }
            if (artCode == null || artCode.isEmpty() || date <= 0) {
                continue;
            }
            AnnouncementJudge.Judgment judgment = AnnouncementJudge.judge(title, column);
            items.add(new Announcement(artCode, date, title, column,
                    judgment.sentiment(), judgment.weight(), judgment.keyword()));
        }
        return items;
    }

    /** "2026-10-31 00:00:00" → 20261031 */
    static int parseDate(String text) {
        if (text == null || text.length() < 10) {
            return 0;
        }
        String digits = text.substring(0, 10).replace("-", "").replace("/", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
