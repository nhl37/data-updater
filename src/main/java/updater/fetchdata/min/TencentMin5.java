package updater.fetchdata.min;

import updater.fetchdata.RealtimeQuoteFetcher;
import updater.fetchdata.tdx.min.TdxMinBar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 在线 5 分钟线路之一：<b>腾讯</b>的逐只 K 线接口（{@code m5}）。
 *
 * <p>这条线路不是来抢主力的，是来兜底的：接口最省事（不带 Referer、响应小、几乎不断连），
 * 东财和新浪同时被限流时它往往还能通。代价是<b>只给成交量，不给成交额</b>，
 * 而且一次最多 640 根（约 13 个交易日）。</p>
 *
 * <p><b>成交额是按「典型价 × 成交量」折出来的</b>（典型价取 (高+低+收)/3），
 * 误差通常不到 1%：不写 0 是为了让脚本里的成交额类判断不会因为走了备用线路就整段变空，
 * 但真要精确成交额，请让东财或新浪那条来补（它们在前面，正常轮不到这里）。</p>
 *
 * <p>时间戳同样是这一根的结束时刻（一天 09:35 → 15:00 共 48 根），量是「手」，
 * 落库前统一乘 100 换成「股」。行格式：
 * {@code ["202611131405","10.42","10.42","10.42","10.41","15092.00",{},"0.45"]}，
 * 依次是 时间、开、收、高、低、量(手)、（空对象）、换手率 —— 注意是「开收高低」。</p>
 */
public final class TencentMin5 implements Min5BarSource {

    public static final String ID = "tencent";
    public static final TencentMin5 INSTANCE = new TencentMin5();

    private static final String API = "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=%s,m5,,%d";
    /** 先按一天要几根问 */
    private static final int PEEK_BARS = 60;
    /** 这个接口的上限：再往上写就退回 320 根，所以 640 就是能拿到的最多 */
    private static final int MAX_BARS = 640;

    private TencentMin5() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "腾讯";
    }

    @Override
    public List<TdxMinBar> fetch(String code, long lastTime) throws Exception {
        // 库里还没有分钟线就一次拉满；有数据时先按一天问
        List<TdxMinBar> bars = request(code, lastTime > 0 ? PEEK_BARS : MAX_BARS);
        if (bars.isEmpty()) {
            return bars;
        }
        if (lastTime > 0 && bars.get(0).getTime() > lastTime) {
            List<TdxMinBar> deeper = request(code, MAX_BARS);
            if (!deeper.isEmpty()) {
                return deeper;
            }
        }
        return bars;
    }

    private static List<TdxMinBar> request(String code, int bars) throws IOException {
        String symbol = RealtimeQuoteFetcher.toMarketCode(code);
        String url = String.format(Locale.ROOT, API, symbol, bars);
        // 这个接口不校验 Referer，给了反而更容易被当成抓取，所以传 null
        String text = Min5Http.get(url, null);
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        com.alibaba.fastjson2.JSONObject root;
        try {
            root = com.alibaba.fastjson2.JSON.parseObject(text);
        } catch (RuntimeException e) {
            throw new IOException("返回内容无法解析", e);
        }
        com.alibaba.fastjson2.JSONObject data = root == null ? null : root.getJSONObject("data");
        com.alibaba.fastjson2.JSONObject node = data == null ? null : data.getJSONObject(symbol);
        com.alibaba.fastjson2.JSONArray rows = node == null ? null : node.getJSONArray("m5");
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<TdxMinBar> out = new ArrayList<TdxMinBar>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            com.alibaba.fastjson2.JSONArray row = rows.getJSONArray(i);
            if (row == null || row.size() < 6) {
                continue;
            }
            String time = row.getString(0);
            double open = Min5Bars.num(row.getString(1));
            double close = Min5Bars.num(row.getString(2));
            double high = Min5Bars.num(row.getString(3));
            double low = Min5Bars.num(row.getString(4));
            long volShares = Math.round(Min5Bars.num(row.getString(5)) * 100);
            // 没有成交额这一列，按典型价折算（见类注释）
            long amo = Math.round(volShares * (high + low + close) / 3.0);
            TdxMinBar bar = Min5Bars.of(Min5Bars.timeOf(time), open, close, high, low,
                    volShares, amo);
            if (bar != null) {
                out.add(bar);
            }
        }
        Min5Bars.sortByTime(out);
        return out;
    }
}
