package updater.risk;

import updater.name.StockNameStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 代码与名称的对照：东财接口给 6 位（600519），个股库文件名是带市场前缀的（sh600519.db）。
 *
 * <p>三个来源的代码口径不一样，这里是唯一的换算点：</p>
 * <ul>
 *   <li>公告库 f10.db：{@code 600519}</li>
 *   <li>解禁 / 财报 / 股本：{@code 600519} 与 {@code sh600519} 都有</li>
 *   <li>个股行情库文件名：{@code sh600519.db}</li>
 * </ul>
 *
 * <p>名称优先取名称库（stock_info，全市场一份）；名称库没挂载时退化成只用代码，
 * 界面显示代码而不是空白 —— 少个名字不影响看风险。</p>
 */
final class CodeName {

    private static Map<String, String> nameByCode;
    private static Map<String, String> fullByCode;

    private CodeName() {
    }

    static synchronized void load() {
        if (nameByCode != null) {
            return;
        }
        Map<String, String> names = new HashMap<>();
        Map<String, String> fulls = new HashMap<>();
        try (Connection conn = StockNameStore.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT stock_code, stock_name FROM stock_info")) {
            while (rs.next()) {
                String full = trim(rs.getString(1));
                String name = trim(rs.getString(2));
                if (full.length() < 6) {
                    continue;
                }
                String six = six(full);
                fulls.put(six, full);
                names.put(six, name);
            }
        } catch (Exception ignored) {
            // 名称库没挂载：只用代码，不影响风险数据本身
        }
        fullByCode = fulls;
        nameByCode = names;
    }

    /** 名称；查不到时返回 null */
    static String name(String code) {
        load();
        return nameByCode.get(six(code));
    }

    /** 带市场前缀的代码（sh600519）；查不到时按规则推 */
    static String full(String code) {
        load();
        String key = six(code);
        String full = fullByCode.get(key);
        return full != null ? full : guessFull(key);
    }

    /** 统一成 6 位 */
    static String six(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase();
        if (text.length() > 6) {
            text = text.substring(text.length() - 6);
        }
        return text;
    }

    private static String guessFull(String six) {
        if (six.isEmpty()) {
            return six;
        }
        char head = six.charAt(0);
        if (head == '6' || head == '5' || head == '9') {
            return "sh" + six;
        }
        if (head == '4' || head == '8') {
            return "bj" + six;
        }
        return "sz" + six;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
