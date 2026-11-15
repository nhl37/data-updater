package updater.util;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;

public class StockCode {
    String[] shStarts;
    String[] szStarts;
    String[] bjStarts;
    private static  StockCode  instance = null;
    private StockCode() throws ConfigException {
        EnhancedConfigReader reader = AppConfigManager.getInstance().getConfigReader();
        shStarts = reader.getString("stock.sh.start").split(",");
        szStarts = reader.getString("stock.sz.start").split(",");
        bjStarts = reader.getString("stock.bj.start").split(",");
        assert shStarts.length >=2;
        assert szStarts.length >=3;
        assert bjStarts.length >=3;
    }

    private static StockCode getInstance() throws ConfigException {
        if (instance == null) {
            instance = new StockCode();
        }
        return instance;
    }

    /**
     * 沪市：个股段由配置 {@code stock.sh.start} 给出（默认 6），
     * <b>场内基金（5 开头）在这里兜底</b> —— 配置文件里一般不会写 5，
     * 但 ETF/LOF 的库文件同样叫 {@code sh510300.db}，不认就会补不出前缀。
     */
    public static boolean isSh(String code) throws ConfigException {
        if (isShFund(code)) {
            return true;
        }
        StockCode stockCode = getInstance();
        for(String s : stockCode.shStarts) {
            if(code.toUpperCase().startsWith(s.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 深市：个股段由 {@code stock.sz.start} 给出（默认 0、3），
     * <b>场内基金（15 / 16 开头）在这里兜底</b>（159 是 ETF，16x 是 LOF）。
     */
    public static boolean isSz(String code) throws ConfigException {
        if (isSzFund(code)) {
            return true;
        }
        StockCode stockCode = getInstance();
        for(String s : stockCode.szStarts) {
            if(code.toUpperCase().startsWith(s.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    /** 沪市场内基金：5 开头（510~518 / 560~563 / 588 / 501 等） */
    private static boolean isShFund(String code) {
        String digits = sixDigits(code);
        return digits.length() == 6 && digits.charAt(0) == '5';
    }

    /** 深市场内基金：15 / 16 开头 */
    private static boolean isSzFund(String code) {
        String digits = sixDigits(code);
        return digits.length() == 6 && (digits.startsWith("15") || digits.startsWith("16"));
    }

    /**
     * 取 6 位数字代码：{@code sh510300} / {@code 510300.SH} / {@code 510300} 都返回 {@code 510300}。
     * 不是 6 位数字返回空串（交给调用方按原逻辑处理，不在这里猜）。
     */
    private static String sixDigits(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase();
        if (text.length() > 2
                && (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj"))) {
            text = text.substring(2);
        }
        if (text.length() > 3
                && (text.endsWith(".sh") || text.endsWith(".sz") || text.endsWith(".bj"))) {
            text = text.substring(0, text.length() - 3);
        }
        if (text.length() != 6) {
            return "";
        }
        for (int i = 0; i < 6; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return "";
            }
        }
        return text;
    }

    public static boolean isBj(String code) throws ConfigException {
        StockCode stockCode = getInstance();
        for(String s : stockCode.bjStarts) {
            if(code.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    public static String addPrefix(String code) throws ConfigException {
        String result;
        if(isSz(code)) {
            result = "sz"+code;
        } else if(isSh(code)) {
            result = "sh"+code;
        } else if(isBj(code)) {
            result = "bj"+code;
        } else {
            result = code;
        }
        return result;
    }
    /**
     * 纯 6 位代码：去掉 sh/sz/bj 前缀与 .SH/.SZ/.BJ 后缀，只做格式归一，不猜市场。
     * <p>
     * 给「界面上显示代码」用：{@code sh600000}、{@code 600000.SH}、{@code 600000} 都返回 {@code 600000}。
     * 找不到库文件时也不能靠它补前缀——补前缀请用 {@link #addPrefix(String)}。
     *
     * @return 归一后的代码；入参为 null 或空串时返回空串
     */
    public static String plain(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        if (text.length() > 2
                && (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj"))) {
            text = text.substring(2);
        }
        if (text.length() > 3
                && (text.endsWith(".sh") || text.endsWith(".sz") || text.endsWith(".bj"))) {
            text = text.substring(0, text.length() - 3);
        }
        return text;
    }

    /**
     * 去市场前缀，等价于 {@link #plain(String)}。
     * <p>
     * 旧实现按「首位数字」判断有没有前缀，裸码 {@code 600000} 会被当成 {@code sh} 开头而截成
     * {@code 0000}；现在统一走 {@code plain()}，只按真实的 sh/sz/bj 前缀裁剪。
     */
    public static String removePrefix(String code) throws ConfigException {
        return plain(code);
    }
}
