package updater.f10;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 十大流通股东：取最近两个报告期，逐个股东算增减持。
 *
 * <p>接口用东财 F10 的「十大流通股东」（{@code RPT_F10_EH_FREEHOLDERS}），
 * 一次按报告期倒序拉 40 条，正好覆盖 4 个报告期，取前两期做对比。</p>
 *
 * <p>为什么不直接用接口里的 {@code CHANGE_RATIO}：那个字段只在「同一股东上期也在十大里」时才有值，
 * 而且单位/口径随报告期变过。这里自己用「本期持股数 - 上期持股数」按股东名匹配算，
 * 逻辑透明，也能算出「新进」和「退出」——那两个是光看单期表格看不出来的。</p>
 *
 * <p><b>按报告期取数</b>：财报是按季度分别下载的，「下载哪个季度的财报就更新哪个季度的股东」，
 * 所以 {@link #load(String, int, boolean)} 支持指定报告期。指定后：
 * 先按 {@code END_DATE} 过滤拉这一期，再按日期单独拉上一季做对比（单期数据算不出增减持）。
 * 不能拿「最近 40 条历史」当上期——那只覆盖最近约 4 个季度，查更早的季度时上期不在里面，
 * 会算成所有股东都「不变」。</p>
 */
public final class TopHolderRepository {

    private static final String URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPT_F10_EH_FREEHOLDERS&columns=ALL"
                    + "&filter=(SECUCODE%%3D%%22%s%%22)"
                    + "&pageNumber=1&pageSize=40"
                    + "&sortColumns=END_DATE,HOLDER_RANK&sortTypes=-1,1"
                    + "&source=WEB&client=WEB";

    /**
     * 指定报告期：在 SECUCODE 之后追加 {@code (END_DATE='yyyy-MM-dd')}。
     *
     * <p>实测有效：传 2026-06-30 只回这一期的 10 条；传还没披露的季度返回
     * {@code code=9201 返回数据为空}（{@link F10Api#get} 会抛异常，由调用方回落）。</p>
     */
    private static final String URL_BY_PERIOD =
            "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPT_F10_EH_FREEHOLDERS&columns=ALL"
                    + "&filter=(SECUCODE%%3D%%22%s%%22)%%28END_DATE%%3D%%27%s%%27)"
                    + "&pageNumber=1&pageSize=40"
                    + "&sortColumns=END_DATE,HOLDER_RANK&sortTypes=-1,1"
                    + "&source=WEB&client=WEB";

    /** 小于这个百分比（相对上期持股）的变动算噪声，不标增持/减持 */
    private static final double NOISE_PCT = 0.01;

    private TopHolderRepository() {
    }

    /**
     * 一个报告期的十大流通股东
     *
     * @param date       报告期 yyyyMMdd（实际拿到的那一期）
     * @param periodName 报告期名称（如「2026三季报」）
     * @param holders    本期十大（含与上期对比）
     * @param previous   上一报告期日期，0 表示没有上期
     * @param exited     上期在十大、本期掉出去的股东
     * @param requested  请求的报告期；0 表示没指定。与 {@code date} 不等即「该季还没披露，回落到了最新一期」
     */
    public record Period(int date, String periodName, List<TopHolder> holders,
                         int previous, List<TopHolder> exited, int requested) {

        public boolean isEmpty() {
            return holders == null || holders.isEmpty();
        }

        /** 请求的季度没有数据，实际给的是接口最新一期 */
        public boolean isFallback() {
            return requested > 0 && date > 0 && requested != date;
        }
    }

    /** 不限期别：取接口最新一期（K线/财报画面按需看单只时走这条） */
    public static Period load(String code, boolean refresh) throws Exception {
        return load(code, 0, refresh);
    }

    /**
     * 按报告期取十大流通股东。
     *
     * <p>{@code reportDate > 0} 时只取这一期（拉两页：本期 + 上期做对比）。
     * 该季东财还没有数据（比如导入 20261231 但年报没披露）时<b>回落</b>到接口最新一期，
     * 不抛异常 —— 批量刷全市场时一只报错就会少一只，回落至少能显示现有最新的股东。</p>
     *
     * @param reportDate 报告期 yyyyMMdd；0 表示不限定期别
     * @param refresh    true 忽略缓存强制联网
     */
    public static Period load(String code, int reportDate, boolean refresh) throws Exception {
        String plain = F10Api.plain(code);
        if (plain.isEmpty()) {
            return new Period(0, "", new ArrayList<>(), 0, new ArrayList<>(), reportDate);
        }
        int target = reportDate > 0 ? reportDate : 0;
        // 缓存按报告期分桶：不同季度互不覆盖（见 F10Store#holderKind）
        String kind = F10Store.holderKind(target);
        String payload = refresh ? null : F10Store.holderCache(plain, kind, F10Store.TTL_HOLDER);
        boolean fromCache = payload != null;
        if (payload == null) {
            payload = fetch(plain, target);
            F10Store.putHolderCache(plain, kind, payload);
        }
        Period period = parse(payload, target);
        if (fromCache && target > 0 && period.date() == target && period.previous() == 0) {
            // 旧缓存只存了本期、没有上期，算出来全是「不变」：补抓一次并覆盖
            try {
                String refreshed = fetch(plain, target);
                Period updated = parse(refreshed, target);
                if (updated.date() == target) {
                    F10Store.putHolderCache(plain, kind, refreshed);
                    period = updated;
                }
            } catch (Exception ignored) {
                // 补不到上期就先显示缓存里的本期
            }
        }
        if (target > 0 && period.date() != target) {
            // 该季没披露（或接口暂时为空）→ 已回落，缓存里存的就是回落结果，不必再抓一次
            return new Period(period.date(), period.periodName(), period.holders(),
                    period.previous(), period.exited(), target);
        }
        return period;
    }

    /**
     * 联网取原始 JSON：指定报告期时把「本期」和「上一季」两份合并成一份，
     * 这样 {@link #parse(String, int)} 能在里面同时找到本期与上期做增减持对比。
     *
     * <p>上一季必须按日期单独请求。历史页只回最近 40 条（约 4 个季度），
     * 用它当上期时，查 4 个季度以前的报告期会找不到任何更早的日期，全部算成「不变」。</p>
     */
    private static String fetch(String plain, int reportDate) throws Exception {
        String secuCode = F10Api.secuCode(plain);
        if (reportDate <= 0) {
            return F10Api.get(String.format(URL, secuCode));
        }
        String current;
        try {
            current = F10Api.get(String.format(URL_BY_PERIOD, secuCode, sqlDate(reportDate)));
        } catch (Exception e) {
            if (!isEmptyResult(e)) {
                throw e;
            }
            // 该季还没披露：退回「不限定期别」，让 parse 回落并标记
            return F10Api.get(String.format(URL, secuCode));
        }
        int previousDate = previousPeriod(reportDate);
        if (previousDate > 0) {
            String previous = null;
            try {
                previous = F10Api.get(String.format(URL_BY_PERIOD, secuCode, sqlDate(previousDate)));
            } catch (Exception ignored) {
                // 上一季可能还没披露，或这一次请求失败：下面用历史页兜底
            }
            if (previous != null) {
                return mergeData(current, previous);
            }
        }
        // 上一季缺报时，最近的季度仍可能在历史页里找到更早的一期
        try {
            return mergeData(current, F10Api.get(String.format(URL, secuCode)));
        } catch (Exception e) {
            return current;
        }
    }

    /** 报告期 yyyyMMdd → 上一个季末；不是标准季末时返回 0 */
    static int previousPeriod(int reportDate) {
        int year = reportDate / 10000;
        switch (reportDate % 10000) {
            case 331:
                return (year - 1) * 10000 + 1231;
            case 630:
                return year * 10000 + 331;
            case 930:
                return year * 10000 + 630;
            case 1231:
                return year * 10000 + 930;
            default:
                return 0;
        }
    }

    /**
     * 合并两页的 {@code result.data} 数组。
     *
     * <p>按 <b>(END_DATE, HOLDER_RANK)</b> 去重：两页里重叠的报告期各不相同
     * （本期页只有目标季，历史页还含目标季本身），直接拼接会让同一期的股东出现两份。</p>
     */
    private static String mergeData(String first, String second) {
        JSONObject a = JSONObject.parseObject(first);
        JSONObject b = JSONObject.parseObject(second);
        JSONObject root = (a != null && a.getJSONObject("result") != null) ? a
                : (b != null && b.getJSONObject("result") != null) ? b : null;
        if (root == null) {
            return first;
        }
        JSONArray merged = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        appendUnique(merged, seen, a);
        appendUnique(merged, seen, b);
        JSONObject result = root.getJSONObject("result");
        result.put("data", merged);
        root.put("result", result);
        return root.toJSONString();
    }

    /** 按 (END_DATE, HOLDER_RANK) 去重后追加 */
    private static void appendUnique(JSONArray target, java.util.Set<String> seen, JSONObject root) {
        JSONObject result = root == null ? null : root.getJSONObject("result");
        JSONArray data = result == null ? null : result.getJSONArray("data");
        if (data == null) {
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String key = item.getString("END_DATE") + "#" + item.getIntValue("HOLDER_RANK")
                    + "#" + item.getString("HOLDER_NAME");
            if (seen.add(key)) {
                target.add(item);
            }
        }
    }

    /** 接口「返回数据为空 / 没有记录」这类结果，按「该季没披露」处理，不算失败 */
    private static boolean isEmptyResult(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("9201") || message.contains("返回数据为空") || message.contains("null");
    }

    /** 报告期 yyyyMMdd → 接口要的 yyyy-MM-dd */
    private static String sqlDate(int reportDate) {
        String text = String.valueOf(reportDate);
        if (text.length() != 8) {
            return text;
        }
        return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8);
    }

    /**
     * 解析接口返回：按 END_DATE 分组成若干报告期，取最近两期做对比。
     */
    static Period parse(String payload) {
        return parse(payload, 0);
    }

    /**
     * 解析接口返回。
     *
     * @param requested 期望的报告期；0 表示取最新一期。给了值但没有该期数据时，
     *                  自动回落成「不晚于该期的最近一期」，仍没有就取最新一期
     */
    static Period parse(String payload, int requested) {
        List<TopHolder> empty = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return new Period(0, "", empty, 0, empty, requested);
        }
        JSONObject root = JSONObject.parseObject(payload);
        JSONObject result = root == null ? null : root.getJSONObject("result");
        JSONArray data = result == null ? null : result.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return new Period(0, "", empty, 0, empty, requested);
        }

        // 报告期 → 该期的股东；接口已按 END_DATE 倒序，LinkedHashMap 保持顺序
        Map<Integer, List<TopHolder>> byDate = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null) {
                continue;
            }
            int date = AnnouncementRepository.parseDate(item.getString("END_DATE"));
            if (date <= 0) {
                continue;
            }
            long holdNum = item.getLongValue("HOLD_NUM");
            double freeRatio = item.getDoubleValue("FREE_HOLDNUM_RATIO");
            String name = item.getString("HOLDER_NAME");
            String type = item.getString("HOLDER_NEWTYPE");
            int rank = item.getIntValue("HOLDER_RANK");
            byDate.computeIfAbsent(date, k -> new ArrayList<>())
                    .add(new TopHolder(rank, name == null ? "" : name,
                            type == null ? "" : type, holdNum, freeRatio,
                            0, null, TopHolder.Kind.HOLD));
            names.putIfAbsent(date, item.getString("REPORT_DATE_NAME"));
        }
        if (byDate.isEmpty()) {
            return new Period(0, "", empty, 0, empty, requested);
        }

        List<Integer> dates = new ArrayList<>(byDate.keySet());
        int current = pickCurrent(dates, requested);
        // 上期 = 严格早于本期的最近一期（按季度取数时，接口可能同时给了更晚的季度，不能拿来当「上期」）
        int previous = 0;
        for (int date : dates) {
            if (date < current) {
                previous = date;
                break;
            }
        }
        Map<String, TopHolder> prevMap = new LinkedHashMap<>();
        if (previous > 0) {
            for (TopHolder holder : byDate.get(previous)) {
                prevMap.put(holder.name(), holder);
            }
        }

        List<TopHolder> holders = new ArrayList<>();
        for (TopHolder holder : byDate.get(current)) {
            if (prevMap.isEmpty()) {
                // 只有一个报告期，没有可比对象：全部按「不变」显示，不硬造增持
                holders.add(holder);
                continue;
            }
            TopHolder before = prevMap.remove(holder.name());
            if (before == null) {
                holders.add(new TopHolder(holder.rank(), holder.name(), holder.type(),
                        holder.holdNum(), holder.freeRatio(), holder.holdNum(), null, TopHolder.Kind.NEW));
                continue;
            }
            long diff = holder.holdNum() - before.holdNum();
            Double pct = before.holdNum() == 0 ? null : diff * 100.0 / before.holdNum();
            TopHolder.Kind kind = diff > 0 ? TopHolder.Kind.ADD
                    : diff < 0 ? TopHolder.Kind.CUT : TopHolder.Kind.HOLD;
            // 名义持有人（香港中央结算这类）每期都有几百几千股的机械微调，
            // 占持仓不到万分之一，标成「增持」纯属噪声
            if (kind != TopHolder.Kind.HOLD && pct != null && Math.abs(pct) < NOISE_PCT) {
                kind = TopHolder.Kind.HOLD;
            }
            holders.add(new TopHolder(holder.rank(), holder.name(), holder.type(),
                    holder.holdNum(), holder.freeRatio(), diff, pct, kind));
        }

        // 上期在十大、这期掉出去的：相当于把这部分全卖了，按 -100% 显示
        List<TopHolder> exited = new ArrayList<>();
        for (TopHolder gone : prevMap.values()) {
            exited.add(new TopHolder(gone.rank(), gone.name(), gone.type(),
                    gone.holdNum(), gone.freeRatio(), -gone.holdNum(), -100.0, TopHolder.Kind.EXIT));
        }
        holders.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        return new Period(current, names.getOrDefault(current, ""), holders, previous, exited,
                requested);
    }

    /**
     * 挑「本期」：
     * <ul>
     *   <li>没指定报告期 → 最新一期（原行为）；</li>
     *   <li>指定了且有这一期 → 就是它；</li>
     *   <li>指定了但接口没有这一期（还没披露）→ 回落到不晚于该期的最近一期，
     *       再不行就用最新一期，保证界面永远有东西看。</li>
     * </ul>
     *
     * @param dates 倒序的报告期列表
     */
    private static int pickCurrent(List<Integer> dates, int requested) {
        if (requested <= 0) {
            return dates.get(0);
        }
        for (int date : dates) {
            if (date == requested) {
                return date;
            }
        }
        for (int date : dates) {
            if (date < requested) {
                return date;
            }
        }
        return dates.get(0);
    }
}
