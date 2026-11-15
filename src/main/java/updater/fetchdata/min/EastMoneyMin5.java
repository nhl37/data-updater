package updater.fetchdata.min;

import updater.fetchdata.tdx.min.TdxMinBar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 在线 5 分钟线路之一：<b>东方财富</b>的逐只 K 线接口。
 *
 * <p>能拿到的历史比另外两家深（配合翻页约 60 个交易日），而且量、额、价都齐，
 * 所以在线这一档默认它先上（见 {@link Min5Online})。</p>
 *
 * <p><b>字段顺序是「开收高低」不是 OHLC</b>：按 OHLC 读会把最高价写成收盘价，
 * 而且这种错不会报错，只会让所有分钟形态判断全错。所以这里逐字段点名取值，
 * 绝不按下标硬套。</p>
 *
 * <p>写库不在这里：这只取到多少根交回 {@link Min5Online}，由它统一走
 * {@code MinDataUpdater.writeBars}（只补不删，比库里末根更晚的才插）。</p>
 */
public final class EastMoneyMin5 implements Min5BarSource {

    public static final String ID = "eastmoney";
    public static final EastMoneyMin5 INSTANCE = new EastMoneyMin5();

    private static final String API = "https://push2his.eastmoney.com/api/qt/stock/kline/get?"
            + "secid=%s&fields1=f1,f2,f3,f4,f5,f6"
            + "&fields2=f51,f52,f53,f54,f55,f56,f57&klt=5&fqt=0&end=%s&lmt=%d";
    private static final String REFERER = "https://quote.eastmoney.com/";

    /** 一页拿多少根：1000 根约 20 个交易日，正常增量一次就够 */
    private static final int PAGE_SIZE = 1000;
    /** 库里落后太多时最多往前翻几页：3 页约 60 个交易日，更早的缺口留给本地文件补 */
    private static final int MAX_PAGES = 3;

    private EastMoneyMin5() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "东方财富";
    }

    @Override
    public List<TdxMinBar> fetch(String code, long lastTime) throws Exception {
        List<TdxMinBar> bars = new ArrayList<TdxMinBar>();
        String end = "20500101";
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TdxMinBar> part = request(code, end);
            if (part.isEmpty()) {
                break;
            }
            bars.addAll(part);
            long earliest = part.get(0).getTime();
            // 已经追上库里最后一根，不用再往更早翻
            if (lastTime <= 0 || earliest <= lastTime) {
                break;
            }
            end = Long.toString(earliest / 10000);
        }
        Min5Bars.sortByTime(bars);
        return bars;
    }

    /** 取一页（按时间升序）；接口这一年没有数据时返回空表，不抛异常 */
    private static List<TdxMinBar> request(String code, String end) throws IOException {
        String url = String.format(Locale.ROOT, API, secId(code), end, PAGE_SIZE);
        String text = Min5Http.get(url, REFERER);
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
        com.alibaba.fastjson2.JSONArray klines = data == null ? null : data.getJSONArray("klines");
        if (klines == null || klines.isEmpty()) {
            return Collections.emptyList();
        }
        List<TdxMinBar> bars = new ArrayList<TdxMinBar>(klines.size());
        for (int i = 0; i < klines.size(); i++) {
            TdxMinBar bar = parseBar(klines.getString(i));
            if (bar != null) {
                bars.add(bar);
            }
        }
        Min5Bars.sortByTime(bars);
        return bars;
    }

    /**
     * 一行：{@code 2026-11-11 09:35,开,收,高,低,成交量(手),成交额(元),…}
     */
    private static TdxMinBar parseBar(String line) {
        if (line == null) {
            return null;
        }
        String[] f = line.split(",");
        if (f.length < 7) {
            return null;
        }
        long time = Min5Bars.timeOf(f[0]);
        // 这个接口给的是「手」，库里的口径是「股」
        long vol = (long) Math.round(Min5Bars.num(f[5]) * 100);
        long amo = (long) Math.round(Min5Bars.num(f[6]));
        return Min5Bars.of(time, Min5Bars.num(f[1]), Min5Bars.num(f[2]),
                Min5Bars.num(f[3]), Min5Bars.num(f[4]), vol, amo);
    }

    /**
     * 证券标识：沪市 {@code 1.600000}，深市 / 北交所 {@code 0.000001}。
     * 与 {@code RealtimeQuoteFetcher#secId} 同一口径，北交所不会被错标成沪市。
     */
    private static String secId(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        String digits = lower.length() > 6 ? lower.substring(lower.length() - 6) : lower;
        return (lower.startsWith("sh") ? "1." : "0.") + digits;
    }
}
