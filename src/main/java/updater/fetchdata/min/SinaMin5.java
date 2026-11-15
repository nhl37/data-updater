package updater.fetchdata.min;

import updater.fetchdata.RealtimeQuoteFetcher;
import updater.fetchdata.tdx.min.TdxMinBar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 在线 5 分钟线路之一：<b>新浪</b>的逐只 K 线接口（{@code scale=5}）。
 *
 * <p>跟东财完全同源的另一套数据：成交量、成交额都给，所以落库口径不用打折。
 * 一次最多给 1023 根（约 21 个交易日），比东财的翻页窗口浅，但一次请求就能拿到，
 * 请求数只有东财的三分之一 —— 东财被限流时它往往还能通。</p>
 *
 * <p><b>时间戳是这一根的结束时刻</b>：一天从 09:35 到 15:00 共 48 根，跟库里口径一致，
 * 不需要做任何偏移。字段顺序是「开高低收」（跟东财的「开收高低」不同），
 * 所以两边都用 {@link Min5Bars} 点名取字段，不按下标硬套。</p>
 */
public final class SinaMin5 implements Min5BarSource {

    public static final String ID = "sina";
    public static final SinaMin5 INSTANCE = new SinaMin5();

    private static final String API =
            "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData"
                    + "?symbol=%s&scale=5&ma=no&datalen=%d";
    private static final String REFERER = "https://finance.sina.com.cn";
    /** 先按一天要几根问：绝大多数时候库里只差当天这 48 根，不必拉 20 天的量 */
    private static final int PEEK_BARS = 60;
    /** 这个接口的天花板：1023 根，约 21 个交易日 */
    private static final int MAX_BARS = 1023;

    private SinaMin5() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "新浪";
    }

    @Override
    public List<TdxMinBar> fetch(String code, long lastTime) throws Exception {
        // 库里还没有分钟线（新库 / 刚补的第一只）就一次拉满，别只给这一天半天的；
        // 有数据时先按一天问，绝大多数时候差的就是当天那 48 根
        List<TdxMinBar> bars = request(code, lastTime > 0 ? PEEK_BARS : MAX_BARS);
        if (bars.isEmpty()) {
            return bars;
        }
        // 库里落后超过一天：再拉一次上限条数，能补多少补多少（更早的缺口留给本地文件）
        if (lastTime > 0 && bars.get(0).getTime() > lastTime) {
            List<TdxMinBar> deeper = request(code, MAX_BARS);
            if (!deeper.isEmpty()) {
                return deeper;
            }
        }
        return bars;
    }

    /**
     * 返回是一串对象：{@code [{"day":"2026-11-13 14:05:00","open":"10.430","high":"10.430",
     * "low":"10.400","close":"10.410","volume":"1511419","amount":"15740960.58"}, …]}
     *
     * <p>{@code volume} 已经是「股」、{@code amount} 已经是「元」，都不用换算
     * （跟东财的「手」「万元」不一样，别照抄）。</p>
     */
    private static List<TdxMinBar> request(String code, int bars) throws IOException {
        String url = String.format(Locale.ROOT, API, RealtimeQuoteFetcher.toMarketCode(code), bars);
        String text = Min5Http.get(url, REFERER);
        if (text == null || text.trim().isEmpty() || "null".equalsIgnoreCase(text.trim())) {
            return Collections.emptyList();
        }
        com.alibaba.fastjson2.JSONArray rows;
        try {
            rows = com.alibaba.fastjson2.JSON.parseArray(text);
        } catch (RuntimeException e) {
            throw new IOException("返回内容无法解析", e);
        }
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<TdxMinBar> out = new ArrayList<TdxMinBar>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            com.alibaba.fastjson2.JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            TdxMinBar bar = Min5Bars.of(
                    Min5Bars.timeOf(row.getString("day")),
                    Min5Bars.num(row.getString("open")),
                    Min5Bars.num(row.getString("close")),
                    Min5Bars.num(row.getString("high")),
                    Min5Bars.num(row.getString("low")),
                    (long) Math.round(Min5Bars.num(row.getString("volume"))),
                    (long) Math.round(Min5Bars.num(row.getString("amount"))));
            if (bar != null) {
                out.add(bar);
            }
        }
        Min5Bars.sortByTime(out);
        return out;
    }
}
