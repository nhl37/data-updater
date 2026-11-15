package updater.finance;

import updater.db.ShareCapital;
import updater.db.ShareCapitalDBManager;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 换手率的取数入口：按「股票 + 日期」查流通股与大股东占比，算换手率与实际换手率。
 * <p>
 * 口径：
 * <ul>
 *   <li><b>换手率</b> = 成交量(股) / 流通A股 × 100</li>
 *   <li><b>实际换手率</b> = 成交量 / (流通A股 - 前十大流通股东合计持股) × 100
 *       —— 大股东那部分筹码平时不参与交易，扣掉之后才知道「真正在市场上换手的筹码」
 *       被换了几成。前十大合计占比就是 (1 - 实际流通比例)。</li>
 * </ul>
 * 股本只在增发、解禁、送转这些时点变，所以取「不晚于该交易日的最近一条」。
 * 一条都没有时返回 NaN，界面显示「-」，不要去猜。
 * <p>
 * 缓存按股票存整条时间线，并在库文件修改时间变化时自动失效——
 * 更新完股本数据不用重启程序，切回 K 线图就是新数据。
 */
public final class ShareCapitalRepository {

    /** 一只股票的股本时间线（升序） */
    private static final class Series {
        /** 有总股本的日期 */
        int[] totalDates = new int[0];
        double[] totalShares = new double[0];
        /** 有流通股的日期 */
        int[] floatDates = new int[0];
        double[] floatShares = new double[0];
        /** 有十大流通股东占比的日期 */
        int[] ratioDates = new int[0];
        double[] ratio = new double[0];
        boolean empty = true;

        double totalSharesAt(int date) {
            int index = latestIndex(totalDates, date);
            return index < 0 ? Double.NaN : totalShares[index];
        }

        double floatSharesAt(int date) {
            int index = latestIndex(floatDates, date);
            return index < 0 ? Double.NaN : floatShares[index];
        }

        double ratioAt(int date) {
            int index = latestIndex(ratioDates, date);
            if (index < 0) {
                // 该日期早于我们有的第一份股东数据（股东数据只抓到了最近几期）。
                // 用最早那一期近似：大股东结构是慢变量，几年的漂移远小于直接当成 0%、
                // 把「实际换手」算成「换手」——那等于悄悄换掉了指标的定义。
                return ratioDates.length == 0 ? Double.NaN : ratio[0];
            }
            return ratio[index];
        }
    }

    /**
     * 一次换手率计算结果
     */
    public static final class Turnover {
        /** 换手率（%），没有流通股数据时为 NaN */
        public final double turnover;
        /** 实际换手率（%），没有大股东数据时退化成与换手率相同 */
        public final double actual;
        /** 用到的流通A股（股），NaN 表示没有数据 */
        public final double floatShares;

        Turnover(double turnover, double actual, double floatShares) {
            this.turnover = turnover;
            this.actual = actual;
            this.floatShares = floatShares;
        }

        public boolean hasData() {
            return !Double.isNaN(turnover);
        }
    }

    public static final Turnover EMPTY = new Turnover(Double.NaN, Double.NaN, Double.NaN);

    /**
     * 某个交易日「不晚于这一天的最近一条」股本（股）。
     *
     * <p>市值、PE、PB 都要用它：股本只在增发、送转、解禁这些时点跳一次，
     * 取「当时生效的那一条」才是那一天真实的总股本，而不是最新股本往回套。</p>
     */
    public static final class Shares {
        /** 总股本（股），NaN 表示没有数据 */
        public final double total;
        /** 已上市流通A股（股），NaN 表示没有数据 */
        public final double floatA;

        Shares(double total, double floatA) {
            this.total = total;
            this.floatA = floatA;
        }

        public boolean hasTotal() {
            return !Double.isNaN(total) && total > 0;
        }

        public boolean hasFloat() {
            return !Double.isNaN(floatA) && floatA > 0;
        }
    }

    /** 没有股本数据时的返回值：两项都是 NaN，别拿 0 当股本 */
    public static final Shares NO_SHARES = new Shares(Double.NaN, Double.NaN);

    /** 最缓存多少只股票的股本时间线：全市场扫描时不能把几千只都留在内存里 */
    private static final int MAX_SERIES = 512;

    private static final Map<String, Series> CACHE =
            new LinkedHashMap<String, Series>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Series> eldest) {
                    return size() > MAX_SERIES;
                }
            };
    private static long cacheStamp = -1;
    /** 上次检查库文件时间的时间点：一次重绘要给几千根K线求值，不能每条都去 stat 文件 */
    private static long cacheStampCheckedAt;
    private static final long STAMP_CHECK_INTERVAL = 2000L;

    private ShareCapitalRepository() {
    }

    /**
     * 某只股票某一天的换手率
     *
     * @param stockCode 股票代码，sh600000 / 600000 都行
     * @param date      交易日 yyyyMMdd
     * @param volume    成交量（股）
     */
    public static Turnover turnover(String stockCode, int date, long volume) {
        if (volume <= 0) {
            return EMPTY;
        }
        Series series = series(stockCode);
        if (series.empty) {
            return EMPTY;
        }
        double floatShares = series.floatSharesAt(date);
        if (Double.isNaN(floatShares) || floatShares <= 0) {
            return EMPTY;
        }
        double turnover = volume * 100.0 / floatShares;
        double ratio = series.ratioAt(date);
        double actual = turnover;
        if (!Double.isNaN(ratio) && ratio > 0 && ratio < 100) {
            // 前十大合计占比 ratio%，剩下 (100-ratio)% 才是真正在流通的筹码
            actual = turnover * 100.0 / (100.0 - ratio);
        }
        return new Turnover(turnover, actual, floatShares);
    }

    /**
     * 当前缓存里有没有这只股票的数据（给界面判断「要不要提示去更新股本」用）
     */
    public static boolean hasData(String stockCode) {
        return !series(stockCode).empty;
    }

    /**
     * 某只股票某个交易日的股本（总股本 + 流通A股）。
     *
     * <p>两项分开取：股本变动记录可能只带其中一项（akshare 给总股本、股东数据给流通股），
     * 调用方拿到 NaN 时该退回别的来源，而不是把 0 当股本算出一个 0 市值。</p>
     */
    public static Shares shares(String stockCode, int date) {
        Series series = series(stockCode);
        if (series.empty) {
            return NO_SHARES;
        }
        return new Shares(series.totalSharesAt(date), series.floatSharesAt(date));
    }

    /**
     * 清缓存：股本数据更新完调用一次
     */
    public static synchronized void invalidate() {
        CACHE.clear();
        cacheStamp = -1;
    }

    private static synchronized Series series(String stockCode) {
        String code = normalize(stockCode);
        if (code.isEmpty()) {
            return emptySeries();
        }
        long now = System.currentTimeMillis();
        if (now - cacheStampCheckedAt < STAMP_CHECK_INTERVAL) {
            Series cached = CACHE.get(code);
            return cached != null ? cached : loadAndCache(code);
        }
        cacheStampCheckedAt = now;
        long stamp = dbStamp();
        if (stamp != cacheStamp) {
            CACHE.clear();
            cacheStamp = stamp;
        }
        Series cached = CACHE.get(code);
        return cached != null ? cached : loadAndCache(code);
    }

    private static Series loadAndCache(String code) {
        Series loaded = load(code);
        CACHE.put(code, loaded);
        return loaded;
    }

    private static Series emptySeries() {
        return new Series();
    }

    private static long dbStamp() {
        try {
            File file = ShareCapitalDBManager.resolveDbFile();
            return file.isFile() ? file.lastModified() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Series load(String code) {
        Series series = new Series();
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            List<ShareCapital> rows = db.loadList(code);
            if (rows.isEmpty()) {
                return series;
            }
            int floatCount = 0;
            int ratioCount = 0;
            int totalCount = 0;
            for (ShareCapital row : rows) {
                if (row.totalShares != null && row.totalShares > 0) {
                    totalCount++;
                }
                if (row.floatShares != null && row.floatShares > 0) {
                    floatCount++;
                }
                if (row.top10HolderRatio != null && row.top10HolderRatio > 0) {
                    ratioCount++;
                }
            }
            series.totalDates = new int[totalCount];
            series.totalShares = new double[totalCount];
            series.floatDates = new int[floatCount];
            series.floatShares = new double[floatCount];
            series.ratioDates = new int[ratioCount];
            series.ratio = new double[ratioCount];
            int ti = 0;
            int fi = 0;
            int ri = 0;
            for (ShareCapital row : rows) {
                int date = parseDate(row.reportDate);
                if (date <= 0) {
                    continue;
                }
                if (row.totalShares != null && row.totalShares > 0) {
                    series.totalDates[ti] = date;
                    series.totalShares[ti] = row.totalShares;
                    ti++;
                }
                if (row.floatShares != null && row.floatShares > 0) {
                    series.floatDates[fi] = date;
                    series.floatShares[fi] = row.floatShares;
                    fi++;
                }
                if (row.top10HolderRatio != null && row.top10HolderRatio > 0) {
                    series.ratioDates[ri] = date;
                    series.ratio[ri] = row.top10HolderRatio;
                    ri++;
                }
            }
            series.empty = totalCount == 0 && floatCount == 0 && ratioCount == 0;
            return series;
        } catch (Exception e) {
            // 库还没建、表还没建都不该把 K 线图拖垮，当成没有数据
            System.err.println("读取股本数据失败 " + code + ": " + e.getMessage());
            return series;
        } finally {
            db.close();
        }
    }

    private static int parseDate(String text) {
        if (text == null || text.length() < 8) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(0, 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 升序数组里找「不晚于 date 的最后一个下标」，没有则 -1
     */
    private static int latestIndex(int[] dates, int date) {
        if (dates.length == 0 || date <= 0) {
            return -1;
        }
        int low = 0;
        int high = dates.length - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (dates[mid] <= date) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /**
     * 统一成库内代码形态：600000 -> sh600000
     *
     * <p>不用 {@code replaceAll}：这行代码在脚本扫全市场时每根K线都要走一次
     * （换手率、市值都从这里过），{@code String.replaceAll} 每次都重新编译正则，
     * 实测比字符循环慢一个数量级。这里没有别的复杂度，就别为了短一行付这个钱。</p>
     */
    static String normalize(String stockCode) {
        if (stockCode == null) {
            return "";
        }
        String text = stockCode.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj")) {
            return text;
        }
        StringBuilder digits = new StringBuilder(6);
        for (int i = 0; i < text.length() && digits.length() < 6; i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        if (digits.length() < 6) {
            return "";
        }
        String code6 = digits.toString();
        return marketPrefix(code6) + code6;
    }

    private static String marketPrefix(String code6) {
        if (code6.startsWith("60") || code6.startsWith("68") || code6.startsWith("90")
                || code6.startsWith("58") || code6.startsWith("51")) {
            return "sh";
        }
        if (code6.startsWith("43") || code6.startsWith("83") || code6.startsWith("87")
                || code6.startsWith("88") || code6.startsWith("92")) {
            return "bj";
        }
        return "sz";
    }
}
