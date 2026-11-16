package updater.fetchdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「指数数据」任务取哪些指数。
 *
 * <p>这里是一份<b>固定清单</b>，不从名称库、也不从本地目录推断 —— 指数库建在股票库目录里
 * （和个股同一个文件夹、同样的 {@code sh000001.db} 文件名），读本地库的一方一行都不用改；
 * 代价是它们也会被当成候选股票扫到，所以筛选时要把
 * {@code sh000} / {@code sz399} / {@code bj899} 挑出去（见
 * {@link updater.util.SecurityType#isIndex(String)}）。</p>
 *
 * <p>只收常用的宽基与板块指数：清单越长，用户越难判断「脚本里该引哪一只」，而冷门指数
 * 大多数人的策略都用不到。要加的指数直接往 {@link #TABLE} 里加一行即可，取数与建库
 * 不需要额外改动 —— 两条取数线路（本地文件 / 东财 secid）对指数和个股都是同一套规则。</p>
 *
 * <p><b>取哪条线路由画面上的「数据来源」决定</b>，见 {@code UpdateSources}：默认读本地
 * 日线文件（{@code vipdoc/{sh,sz,bj}/lday}，见 {@code IndexLocalFetcher}），备用才走东财
 * 逐只请求（{@code EastMoneyDayFetcher}）。两条线路只补缺的日期，历史以库里已有的为准。</p>
 */
public final class IndexCodes {

    /** 代码 → 台账里显示的名称。顺序就是台账里的行序（先沪后深再北）。 */
    private static final String[][] TABLE = {
            {"sh000001", "上证指数"},
            {"sh000300", "沪深300"},
            {"sh000905", "中证500"},
            {"sh000852", "中证1000"},
            {"sh000688", "科创50"},
            {"sz399001", "深证成指"},
            {"sz399006", "创业板指"},
            {"bj899050", "北证50"},
    };

    private IndexCodes() {
    }

    /** 全部指数代码（规范成 sh000001 这种写法） */
    public static List<String> all() {
        List<String> codes = new ArrayList<>(TABLE.length);
        for (String[] row : TABLE) {
            codes.add(row[0]);
        }
        return Collections.unmodifiableList(codes);
    }

    /** 台账里的显示名；不是清单里的指数就返回代码本身 */
    public static String nameOf(String code) {
        String key = normalize(code);
        for (String[] row : TABLE) {
            if (row[0].equals(key)) {
                return row[1];
            }
        }
        return code == null ? "" : code;
    }

    private static String normalize(String code) {
        String text = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (text.length() == 6) {
            // 裸 6 位：000001 在深市是平安银行，不能猜成上证指数，原样返回让它匹配不上清单
            return text;
        }
        return text;
    }
}
