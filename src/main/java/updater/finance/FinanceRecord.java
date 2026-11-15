package updater.finance;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一条财报记录：某只股票某个报告期的财务字段。
 * <p>
 * 字段按 {@link FinanceFields} 下标存放，缺失的字段为 {@link Double#NaN}。
 * 数据库里的 fields 列是一段 Python 列表文本，形如 {@code [1.23, 4.56, ...]}。
 */
public final class FinanceRecord {

    private final String code;
    /** 报告期 yyyyMMdd */
    private final String reportDate;
    private final double[] values;

    public FinanceRecord(String code, String reportDate, double[] values) {
        this.code = code;
        this.reportDate = reportDate;
        this.values = values == null ? new double[0] : values;
    }

    public String getCode() {
        return code;
    }

    public String getReportDate() {
        return reportDate;
    }

    /**
     * 报告期的显示文本，如 20260930 → 2026-09-30
     */
    public String getReportDateText() {
        return reportDateText(reportDate);
    }

    /**
     * 报告期年份，如 20260930 → 2026；报告期非法时返回 0
     */
    public int getReportYear() {
        return reportYear(reportDate);
    }

    /**
     * 把 yyyyMMdd 的报告期格式化为 yyyy-MM-dd
     */
    public static String reportDateText(String reportDate) {
        if (reportDate == null || reportDate.length() != 8) {
            return reportDate == null ? "-" : reportDate;
        }
        return reportDate.substring(0, 4) + "-" + reportDate.substring(4, 6) + "-" + reportDate.substring(6, 8);
    }

    /**
     * 取报告期的年份，无法识别时返回 0
     */
    public static int reportYear(String reportDate) {
        if (reportDate == null || reportDate.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(reportDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 是否为年报（报告期是 12 月 31 日）
     */
    public boolean isAnnual() {
        return reportDate != null && reportDate.endsWith("1231");
    }

    public boolean has(int index) {
        return index >= 0 && index < values.length && !Double.isNaN(values[index]);
    }

    /**
     * 取某个字段的值，缺失时返回 NaN
     */
    public double value(int index) {
        return index >= 0 && index < values.length ? values[index] : Double.NaN;
    }

    /**
     * 归母净利润，缺失时退回净利润
     */
    public double netProfitParent() {
        double parent = value(FinanceFields.NET_PROFIT_PARENT);
        return Double.isNaN(parent) ? value(FinanceFields.NET_PROFIT) : parent;
    }

    /**
     * 解析数据库 fields 列的文本，只解析到 {@link FinanceFields#MAX_INDEX}
     */
    public static FinanceRecord parse(String code, String reportDate, String text) {
        double[] values = new double[FinanceFields.MAX_INDEX + 1];
        Arrays.fill(values, Double.NaN);
        if (text == null || text.isEmpty()) {
            return new FinanceRecord(code, reportDate, values);
        }
        int length = text.length();
        int index = 0;
        int i = 0;
        while (i < length && index <= FinanceFields.MAX_INDEX) {
            char ch = text.charAt(i);
            // 跳过列表的括号、分隔符与空白
            if (ch == '[' || ch == ']' || ch == ',' || ch == '"' || Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            int start = i;
            while (i < length) {
                char current = text.charAt(i);
                if (current == ',' || current == ']' || current == '[') {
                    break;
                }
                i++;
            }
            values[index] = parseValue(text.substring(start, i));
            index++;
        }
        return new FinanceRecord(code, reportDate, values);
    }

    private static double parseValue(String token) {
        String value = token.trim();
        if (value.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // nan / None / 空串等非数值统一视为缺失
            return Double.NaN;
        }
    }

    /**
     * 按元格式化金额，自动换算成亿/万
     */
    public static String formatAmount(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        double abs = Math.abs(value);
        if (abs >= 1e8) {
            return String.format(Locale.ROOT, "%.2f亿", value / 1e8);
        }
        if (abs >= 1e4) {
            return String.format(Locale.ROOT, "%.2f万", value / 1e4);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 百分比数值（如 12.34 表示 12.34%）
     */
    public static String formatPercent(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f%%", value);
    }

    /**
     * 每股类数值，保留两位小数
     */
    public static String formatNumber(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }
}
