package updater.main.updater;

import updater.f10.F10BulkSync;
import updater.mainwave.StockNameUpdater;
import updater.name.StockNameStore;
import updater.util.ItemProgress;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 「其他数据」这一组的查缺补漏：核股票名称库。
 *
 * <p>真值就是本地已有的个股库清单 —— 有日线库却查不到名字，才是真缺。
 * 名称库没挂载、表不存在、名字是空的，都算缺，台账逐只一行写清楚。</p>
 *
 * <p>修复没法逐只补：抓名称的接口一次给全市场，所以整批缺名字时统一跑一次
 * {@link StockNameUpdater#runUpdate}，再逐只复查。没挂载名称库是配置问题，
 * 不联网，直接告诉用户去「路径设置」。</p>
 */
public final class NameIntegrityChecker {

    private NameIntegrityChecker() {
    }

    public static final class Summary {
        public int total;
        public int ok;
        public int missing;
        public int fixed;
        public int updatedRows;
        public long elapsedMs;

        public String describeScan() {
            return "核对 " + total + " 只：有名称 " + ok + "，缺名称 " + missing
                    + (missing > 0 ? "（点「修复缺失」重新抓一遍全市场名称）" : "，没有发现缺失")
                    + " · 用时 " + formatDuration(elapsedMs);
        }

        public String describeRepair() {
            return "修复完成：重新抓名称 " + (updatedRows > 0 ? updatedRows + " 条，" : "")
                    + "补回 " + fixed + " 只，仍缺 " + (total - ok - fixed)
                    + " 只 · 用时 " + formatDuration(elapsedMs);
        }
    }

    /** 逐只对账 */
    public static Summary scan(Consumer<String> log, ItemProgress items, AtomicBoolean cancel,
                               Collection<String> onlyCodes) throws Exception {
        long start = System.currentTimeMillis();
        Summary summary = new Summary();
        List<String> codes = codesToCheck(onlyCodes);
        report(log, "股票清单 " + codes.size() + " 只（来自本地个股库）");
        if (!StockNameStore.enabled()) {
            report(log, "还没有挂载名称库 stock_name.db：所有股票都查不到名字");
        }
        items.planned(codes);
        Set<String> named = readNamedCodes();
        for (String code : codes) {
            if (cancel != null && cancel.get()) {
                break;
            }
            items.begin(code);
            if (named.contains(plain(code))) {
                summary.ok++;
                items.done(code, "有名称");
            } else {
                summary.missing++;
                items.fail(code, StockNameStore.enabled()
                        ? "名称库里没有这只（或名称为空）" : "没有挂载名称库");
            }
        }
        summary.total = codes.size();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeScan());
        return summary;
    }

    /** 重新抓一遍全市场名称，再逐只复查 */
    public static Summary repair(final Consumer<String> log, ItemProgress items, AtomicBoolean cancel,
                                 Collection<String> onlyCodes) throws Exception {
        long start = System.currentTimeMillis();
        Summary summary = new Summary();
        final List<String> codes = codesToCheck(onlyCodes);
        items.planned(codes);

        if (!StockNameStore.enabled()) {
            for (String code : codes) {
                items.begin(code);
                items.fail(code, "没有挂载名称库，请先到「路径设置」里指定数据文件夹");
            }
            summary.total = codes.size();
            summary.elapsedMs = System.currentTimeMillis() - start;
            report(log, "没有挂载名称库，无法更新名称");
            return summary;
        }

        report(log, "开始重新获取全市场股票名称（一次拉全市场，逐只复查）");
        Set<String> before = readNamedCodes();
        try {
            summary.updatedRows = StockNameUpdater.runUpdate(new Consumer<String>() {
                @Override
                public void accept(String message) {
                    report(log, message);
                }
            });
        } catch (Throwable t) {
            for (String code : codes) {
                items.begin(code);
                items.fail(code, "名称获取失败：" + oneLine(t));
            }
            summary.total = codes.size();
            summary.elapsedMs = System.currentTimeMillis() - start;
            report(log, "名称获取失败：" + oneLine(t));
            return summary;
        }

        Set<String> after = readNamedCodes();
        for (String code : codes) {
            if (cancel != null && cancel.get()) {
                break;
            }
            items.begin(code);
            String key = plain(code);
            if (after.contains(key)) {
                if (before.contains(key)) {
                    items.skip(code, "本来就有名称");
                } else {
                    summary.fixed++;
                    items.done(code, "已补上名称");
                }
                summary.ok++;
            } else {
                items.fail(code, "重新获取后仍然没有名称（可能已退市或接口没有这只）");
            }
        }
        summary.total = codes.size();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeRepair());
        return summary;
    }

    // ================================================================== 工具

    private static List<String> codesToCheck(Collection<String> onlyCodes) {
        if (onlyCodes != null && !onlyCodes.isEmpty()) {
            List<String> out = new ArrayList<String>(new LinkedHashSet<String>(onlyCodes));
            Collections.sort(out);
            return out;
        }
        List<String> codes = F10BulkSync.localStockCodes();
        Collections.sort(codes);
        return codes;
    }

    /** 名称库里「有名字」的代码，统一成 6 位数字 */
    private static Set<String> readNamedCodes() {
        Set<String> out = new HashSet<String>();
        if (!StockNameStore.enabled()) {
            return out;
        }
        Connection conn = null;
        try {
            conn = StockNameStore.open();
            Statement stmt = conn.createStatement();
            try {
                ResultSet rs = stmt.executeQuery(
                        "SELECT stock_code, stock_name FROM " + StockNameStore.TABLE);
                try {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        if (name == null || name.trim().isEmpty()) {
                            continue;
                        }
                        String key = plain(rs.getString(1));
                        if (!key.isEmpty()) {
                            out.add(key);
                        }
                    }
                } finally {
                    rs.close();
                }
            } finally {
                stmt.close();
            }
        } catch (Throwable ignored) {
            // 表还没建 / 库打不开：按「全缺」处理，修复会重建
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // 只读连接，关不掉不影响结论
                }
            }
        }
        return out;
    }

    /** sh600000 / 600000.SH / 600000 → 600000 */
    private static String plain(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.length() >= 6
                ? digits.substring(digits.length() - 6) : digits.toString();
    }

    private static void report(Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        return seconds < 60 ? seconds + " 秒"
                : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private static String oneLine(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
