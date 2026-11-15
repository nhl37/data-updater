package updater.fetchdata.min;

import updater.fetchdata.tdx.min.TdxMinBar;

import java.util.List;

/**
 * 三条在线线路共用的「一根 5 分钟」的造法与校验。
 *
 * <p>各家给的原始字段口径都不一样（东财是逗号串、新浪是对象数组、腾讯是纯数组；量有「手」有「股」，
 * 金额有的给有的不给），但落库必须只有一种口径 —— 全在这里转换，谁也不许自己拼
 * {@link TdxMinBar}，否则迟早出现「同一天的 5 分钟，三条线路写进去的价不一样」。</p>
 *
 * <p>价格一律换成「分」（元 × 100 的整数），成交量一律「股」，成交额一律「元」，时间一律
 * {@code yyyyMMddHHmm} 且取这一根的<b>结束</b>时刻（早盘第一根 0935、尾盘最后一根 1500）。</p>
 */
final class Min5Bars {

    private Min5Bars() {
    }

    /**
     * 造一根，脏数据返回 null（宁可缺一根，也不要写进高低价互相矛盾的记录）。
     *
     * @param open  开盘（元）
     * @param close 收盘（元）
     * @param high  最高（元）
     * @param low   最低（元）
     * @param vol   成交量（股）
     * @param amo   成交额（元），没有就传 0
     */
    static TdxMinBar of(long time, double open, double close, double high, double low,
                        long vol, long amo) {
        if (time <= 0) {
            return null;
        }
        int o = cents(open);
        int c = cents(close);
        int h = cents(high);
        int l = cents(low);
        if (o <= 0 || c <= 0 || h <= 0 || l <= 0) {
            return null;
        }
        // 高低价与开收对不上的一律丢：写进库比缺一根更难查
        if (l > Math.min(o, c) || h < Math.max(o, c) || l > h) {
            return null;
        }
        return new TdxMinBar(time, o, h, l, c, Math.max(0L, vol), Math.max(0L, amo));
    }

    /** 元 → 分（四舍五入到分） */
    static int cents(double yuan) {
        return yuan <= 0 ? 0 : (int) Math.round(yuan * 100);
    }

    /**
     * 时间文本 → {@code yyyyMMddHHmm}。
     *
     * <p>认得了这几种写法：{@code 2026-11-13 09:35}、{@code 2026-11-13 09:35:00}、
     * {@code 202611130935}、{@code 20261113093500}。只取前 12 位数字，秒一律丢掉。</p>
     */
    static long timeOf(String text) {
        if (text == null) {
            return 0L;
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() < 12) {
            return 0L;
        }
        try {
            return Long.parseLong(digits.substring(0, 12));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 数字文本 → double；空 / 非数字一律 0 */
    static double num(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 按时间升序（各家返回顺序不保证，落库前统一排一遍） */
    static void sortByTime(List<TdxMinBar> bars) {
        if (bars == null || bars.size() < 2) {
            return;
        }
        bars.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));
    }
}
