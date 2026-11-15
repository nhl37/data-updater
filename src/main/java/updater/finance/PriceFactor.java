package updater.finance;

import updater.db.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前复权因子时间线：把K线上的<b>复权价</b>折回「那一天真实的原始价」。
 *
 * <p><b>为什么需要它</b>：脚本节点上的 C/O/H/L 是<b>前复权</b>价
 * （{@code SqlText.DAY} 里乘了累计除权因子，把历史价格全部换算到最新价基准上）。
 * 算市值、市盈率、市净率要的是「那天真实的股价 × 那天真实的总股本」，
 * 而送转、除息之后前复权价只有原始价的几分之一甚至几十分之一 ——
 * 直接乘下去，一只 10 送 10 的股票历史市值会凭空少一半，PE 也跟着对折。
 * 所以先按当日因子折回原始价：{@code 原始价 = 复权价 / 因子}，与
 * {@code DailyBarRepository.factor}、{@code BarDataEditorDialog} 折回写库的口径一致。</p>
 *
 * <p><b>口径</b>：因子取「这一天之后最近一次除权的因子」，那一天之后没有除权事件就是 1。
 * 这和 {@code T_Divdend} 的写入方式一致（每条记录存的是从该日起生效的累计因子），
 * 也和日线 SQL 里的 {@code COALESCE((SELECT factor ... WHERE time > t1.time), 1)} 一致。</p>
 *
 * <p><b>为什么要缓存</b>：脚本扫全市场时每根K线都要问一次「这天的因子」，
 * 一天一次查库就是几千万次查询。除权表很小（一只票几十行），按股票读一次放内存即可。</p>
 */
public final class PriceFactor {

    /** 缓存多少只股票的除权时间线；一只几十行，512 只不到 1MB */
    private static final int MAX_CODES = 512;
    /** 多久检查一次库文件是否被改写：一次重绘要给几千根K线求值，不能每条都去 stat 文件 */
    private static final long STAMP_CHECK_INTERVAL = 5000L;

    private static final class Series {
        /** 除权日 yyyyMMdd，升序 */
        int[] dates = new int[0];
        /** 与 dates 同序的累计因子 */
        double[] factors = new double[0];
        /** 库文件修改时间，变了说明除权数据被重写过 */
        long stamp;
        long checkedAt;
    }

    private static final Map<String, Series> CACHE =
            new LinkedHashMap<String, Series>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Series> eldest) {
                    return size() > MAX_CODES;
                }
            };

    private PriceFactor() {
    }

    /**
     * 某个交易日的累计除权因子；没有除权（或读不到除权表）返回 1。
     *
     * @param date yyyyMMdd
     */
    public static double at(String stockCode, int date) {
        String code = normalize(stockCode);
        if (code.isEmpty() || date <= 0) {
            return 1.0;
        }
        Series series = series(code);
        int index = firstAfter(series.dates, date);
        if (index < 0) {
            return 1.0;
        }
        double factor = series.factors[index];
        // 0 / 负数 / NaN 的因子只会把价格算成 0 或负，宁可当没除权
        return factor > 0 && !Double.isNaN(factor) ? factor : 1.0;
    }

    /** 清缓存：除权数据更新完调用一次 */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    private static synchronized Series series(String code) {
        Series cached = CACHE.get(code);
        long now = System.currentTimeMillis();
        if (cached != null) {
            if (now - cached.checkedAt < STAMP_CHECK_INTERVAL) {
                return cached;
            }
            cached.checkedAt = now;
            long stamp = dbStamp(code);
            if (stamp == cached.stamp) {
                return cached;
            }
        }
        Series loaded = load(code);
        CACHE.put(code, loaded);
        return loaded;
    }

    /** 升序数组里第一个「严格大于 date」的下标；没有返回 -1 */
    private static int firstAfter(int[] dates, int date) {
        int low = 0;
        int high = dates.length - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (dates[mid] > date) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return result;
    }

    private static long dbStamp(String code) {
        try {
            java.io.File file = new java.io.File(DBConnection.dbFilePath(code));
            return file.isFile() ? file.lastModified() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Series load(String code) {
        Series series = new Series();
        series.checkedAt = System.currentTimeMillis();
        series.stamp = dbStamp(code);
        Connection conn = null;
        try {
            conn = DBConnection.connect(code);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TIME, FACTOR FROM T_Divdend ORDER BY TIME ASC");
                 ResultSet rs = ps.executeQuery()) {
                List<Integer> times = new java.util.ArrayList<>();
                List<Double> factors = new java.util.ArrayList<>();
                while (rs.next()) {
                    double factor = rs.getDouble(2);
                    if (factor > 0 && !Double.isNaN(factor)) {
                        times.add(rs.getInt(1));
                        factors.add(factor);
                    }
                }
                series.dates = new int[times.size()];
                series.factors = new double[factors.size()];
                for (int i = 0; i < times.size(); i++) {
                    series.dates[i] = times.get(i);
                    series.factors[i] = factors.get(i);
                }
            }
        } catch (Exception e) {
            // 库不存在 / 没有 T_Divdend（挂载说明里它是可选表）都当「没有除权」，
            // 不要把K线脚本整条链路拖垮
            series.dates = new int[0];
            series.factors = new double[0];
        } finally {
            closeQuietly(conn);
        }
        return series;
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关不掉不影响结果
            }
        }
    }

    /** 统一成库内代码形态：600000 → sh600000 */
    private static String normalize(String stockCode) {
        return ShareCapitalRepository.normalize(stockCode);
    }
}
