package updater.util;

/**
 * 证券类别与「量价口径」判断。
 *
 * <p>更新器要维护两类品种：个股（沪深京）与<b>场内基金</b>（ETF / LOF）。
 * 这两类在本地数据来源的文件里，价格的整数倍数、成交量的单位都不一样，
 * 判断和换算统一放在这里 —— 别的类不要再各写一套 {@code startsWith} 判断。</p>
 *
 * <p><b>只按 6 位代码段判断，不读配置</b>：更新器可能在没有挂载、没有 config.properties
 * 的环境里被单独启动，这类判断不能依赖配置项。</p>
 *
 * <pre>
 *   沪市基金：5 开头（500/501/502/505/510~518/560~563/588/589）
 *   深市基金：15 / 16 开头（159 是 ETF，160~169 是 LOF，150~158 是分级）
 * </pre>
 *
 * <p>指数（sh000001 / sz399001）、债券（10/11/12 开头）、B 股（900）都不算个股也不算场内基金，
 * 一律返回 false，由调用方跳过。</p>
 */
public final class SecurityType {

    private SecurityType() {
    }

    /**
     * 取 6 位代码：{@code sh510300} / {@code sh510300.day} / {@code 510300.SH} 都返回 {@code 510300}。
     *
     * @return 6 位数字；不是 6 位数字（含空、null）返回空串
     */
    public static String digits6(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase(java.util.Locale.ROOT);
        // 允许直接传路径（vipdoc/sh/lday/sh510300.day），只认最后一段文件名
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        if (text.length() > 2
                && (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj"))) {
            text = text.substring(2);
        }
        int dot = text.lastIndexOf('.');
        if (dot > 0) {
            text = text.substring(0, dot);
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

    /** 是不是场内基金（ETF / LOF）。北交所、指数、债券都不在内。 */
    public static boolean isFund(String code) {
        String digits = digits6(code);
        if (digits.isEmpty()) {
            return false;
        }
        return digits.charAt(0) == '5'
                || digits.startsWith("15")
                || digits.startsWith("16");
    }

    /** 是不是个股（沪深主板 / 创业板 / 科创板 / 北交所） */
    public static boolean isStock(String code) {
        String digits = digits6(code);
        if (digits.isEmpty() || isFund(digits)) {
            return false;
        }
        if (digits.startsWith("6")) {
            return true;                       // 沪市主板 + 科创板
        }
        if (digits.startsWith("0") || (digits.startsWith("3") && !digits.startsWith("39"))) {
            return true;                       // 深市主板 / 创业板（399 是指数）
        }
        return digits.startsWith("43") || digits.startsWith("83") || digits.startsWith("87")
                || digits.startsWith("92");    // 北交所
    }

    /**
     * 是不是指数。
     *
     * <p>指数和个股共用同一套 6 位代码，靠<b>市场前缀</b>才分得开：沪市 {@code sh000001}
     * 是上证指数，深市 {@code sz000001} 是平安银行。所以这里不加前缀一律返回 false ——
     * 裸的 {@code 000001} 只能当个股，猜成指数会把平安银行当成大盘。</p>
     *
     * <pre>
     *   沪市指数：sh000xxx（000001 上证 / 000300 沪深300 / 000905 中证500 / 000688 科创50）
     *   深市指数：sz399xxx（399001 深证成指 / 399006 创业板指）
     *   北证指数：bj899xxx（899050 北证50）
     * </pre>
     */
    public static boolean isIndex(String code) {
        if (code == null) {
            return false;
        }
        String text = code.trim().toLowerCase(java.util.Locale.ROOT);
        // 允许直接传路径（vipdoc/sh/lday/sh000001.day），只认最后一段文件名
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        String digits = digits6(text);
        if (digits.isEmpty() || text.length() < 8) {
            return false;
        }
        String market = text.substring(0, 2);
        if ("sh".equals(market)) {
            return digits.startsWith("000");
        }
        if ("sz".equals(market)) {
            return digits.startsWith("399");
        }
        if ("bj".equals(market)) {
            return digits.startsWith("899");
        }
        return false;
    }

    /**
     * 本地文件里价格的整数倍数：个股是「分」（×100），基金是「厘」（×1000）。
     *
     * <p>实测本地数据来源的基金日线：{@code sh510300.day} 收盘 4150 表示 4.150 元，
     * {@code sz159915.day} 收盘 2590 表示 2.590 元。个股 997 表示 9.97 元。
     * 用同一个 100 去读基金，价格会整体放大 10 倍，而且不会报错。</p>
     */
    public static int priceDivisor(String code) {
        return isFund(code) ? 1000 : 100;
    }

    /**
     * 成交量归一到「股 / 份」。
     *
     * <p>本地日线里，基金的成交量单位<b>不统一</b>：同一只 ETF 有些交易日是「份」，
     * 有些交易日是「手」（差 100 倍），看起来跟当日数据来源有关。个股没有这个问题。
     * 这里用成交额定标：{@code 成交额 ÷ (成交量 × 价格)} 在「份」下约等于 1，
     * 在「手」下约等于 100，落在这中间就按「手」处理乘 100。</p>
     *
     * <p>只认 20~500 这一段：区间外的比值是脏数据（停牌、字段错位），原样返回，
     * 交给上层的价格 / 量额自检去丢弃，不在这里猜。</p>
     *
     * @param volume 原始成交量
     * @param amount 成交额（元）
     * @param price  当日收盘价（元）
     */
    public static long normalizeVolume(long volume, double amount, double price) {
        if (volume <= 0 || amount <= 0 || price <= 0) {
            return volume;
        }
        double implied = amount / (volume * price);
        if (implied > 20.0 && implied < 500.0) {
            return volume * 100L;
        }
        return volume;
    }
}
