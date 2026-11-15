package updater.pit;

import updater.f10.F10Api;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉取财报「实际披露日」，落 {@link PitStore}。
 *
 * <p>数据源是东财业绩报表 {@code RPT_LICO_FN_CPD}，里面的 {@code NOTICE_DATE}
 * 就是这期财报实际挂网的日子。实测每页 500 行（传 2000 也只给 500），
 * 三年 12 个报告期约 9.3 万行 = 186 页，一页 0.3 秒上下，整轮一分钟左右。</p>
 *
 * <p>为什么不从本地公告库（f10.db {@code T_Announcement}）抓「季度报告」标题：
 * 那个库是按用户看过的股票增量积累的，全市场只覆盖 1.1 万条、季报只有 136 份，
 * 拿它做全市场回测就是拿抽样当全集。这条路只在没有外网时才值得退守。</p>
 */
public final class PitSync {

    private static final String REFERER = "https://data.eastmoney.com/";
    private static final int TIMEOUT_MS = 20000;
    /** 实测上限就是 500，传更大也只回 500 */
    private static final int PAGE_SIZE = 500;

    /** 与 {@code RiskSync.Progress} 同形，但不跨包引用，免得 pit 反过来依赖 risk */
    public interface Progress {
        void step(int done, int total, String text);

        boolean cancelled();
    }

    public record Result(int rows, String note) {
    }

    private PitSync() {
    }

    /**
     * 同步 {@code fromYear} 年起所有已披露报告期的实际披露日。
     *
     * @param fromYear 起始年份，往前拉得越多年限越长：一年约 62 页、三年约 186 页
     */
    public static Result sync(int fromYear, Progress progress) {
        String from = fromYear + "-01-01";
        String filter = "(REPORTDATE>='" + from + "')";
        List<PitStore.Notice> list = new ArrayList<>(120000);
        try {
            int pages = 1;
            for (int page = 1; page <= pages; page++) {
                if (progress != null && progress.cancelled()) {
                    return new Result(0, "已停止，本次没有写入");
                }
                JSONObject root = JSONObject.parseObject(
                        F10Api.get(url(page, filter), REFERER, TIMEOUT_MS));
                if (root == null || !root.getBooleanValue("success")) {
                    throw new IllegalStateException(root == null ? "无响应"
                            : String.valueOf(root.getString("message")));
                }
                JSONObject result = root.getJSONObject("result");
                if (result == null) {
                    break;
                }
                pages = Math.max(1, result.getIntValue("pages"));
                JSONArray data = result.getJSONArray("data");
                if (data == null) {
                    break;
                }
                for (int i = 0; i < data.size(); i++) {
                    JSONObject row = data.getJSONObject(i);
                    String code = PitStore.six(row.getString("SECURITY_CODE"));
                    int reportDate = number(row.getString("REPORTDATE"));
                    int noticeDate = number(row.getString("NOTICE_DATE"));
                    if (code.isEmpty() || reportDate <= 0 || noticeDate <= 0) {
                        continue;
                    }
                    list.add(new PitStore.Notice(code, reportDate, noticeDate));
                }
                if (progress != null) {
                    progress.step(page, pages, "第 " + page + "/" + pages + " 页 · 已收 "
                            + list.size() + " 条披露记录");
                }
            }
        } catch (Exception e) {
            return new Result(0, "拉取披露日期失败：" + e.getMessage());
        }
        if (list.isEmpty()) {
            return new Result(0, "接口没有返回数据，可能被限流，稍后再试");
        }
        int rows = PitStore.replaceAll(fromYear * 10000 + 101, list);
        return new Result(rows, "披露日期 " + rows + " 条（覆盖 " + from + " 起的报告期）");
    }

    private static String url(int page, String filter) {
        return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                + "?reportName=RPT_LICO_FN_CPD"
                + "&columns=SECURITY_CODE,REPORTDATE,NOTICE_DATE"
                + "&pageSize=" + PAGE_SIZE
                + "&pageNumber=" + page
                + "&sortColumns=REPORTDATE,SECURITY_CODE&sortTypes=-1,1"
                + "&filter=" + encode(filter);
    }

    /** "2026-10-24 00:00:00" → 20261024 */
    private static int number(String text) {
        if (text == null) {
            return 0;
        }
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < text.length() && sb.length() < 8; i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        if (sb.length() < 8) {
            return 0;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String text) {
        return text.replace("'", "%27").replace(">", "%3E").replace("<", "%3C")
                .replace("=", "%3D").replace("(", "%28").replace(")", "%29")
                .replace(" ", "%20");
    }
}
