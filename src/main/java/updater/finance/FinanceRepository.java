package updater.finance;

import updater.db.DBException;
import updater.db.FinanceDBManager;
import updater.fetchdata.tdx.finance.FinanceFetchConfig;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 财报数据查询：直接读挂载的财务库（mount.properties 里的 mount.finance.file）的 T_D_Finance 表。
 * <p>
 * 表里每只股票每个报告期一行，财务字段放在 fields 列（gpcw 原始字段数组的文本），
 * 字段含义见 {@link FinanceFields}。
 */
public final class FinanceRepository {

    /** fields 列名（SQLite 里作为普通标识符使用，加引号避免歧义） */
    private static final String COL_FIELDS = "\"fields\"";

    /** 财报画面默认读取的报告期数量（约 6 年，足够看多年趋势） */
    public static final int DEFAULT_LIMIT = 24;

    private FinanceRepository() {
    }

    /**
     * 读取某只股票最近的若干个报告期（按报告期倒序），无数据时返回空列表
     */
    public static List<FinanceRecord> loadRecords(String stockCode, int limit) throws DBException {
        List<String> codes = candidateCodes(stockCode);
        if (codes.isEmpty() || !dbFileExists()) {
            return Collections.emptyList();
        }
        FinanceDBManager db = openDb();
        try {
            if (!hasFieldsColumn(db)) {
                return Collections.emptyList();
            }
            StringBuilder sql = new StringBuilder("SELECT code, report_date, ")
                    .append(COL_FIELDS)
                    .append(" FROM ").append(FinanceDBManager.TABLE_FINANCE)
                    .append(" WHERE code IN (");
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(") ORDER BY report_date DESC LIMIT ").append(Math.max(1, limit));

            try (PreparedStatement pstmt = db.getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < codes.size(); i++) {
                    pstmt.setString(i + 1, codes.get(i));
                }
                List<FinanceRecord> result = new ArrayList<>();
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(FinanceRecord.parse(rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DBException("查询财报数据失败: " + stockCode, e);
        } finally {
            db.close();
        }
    }

    /**
     * 清掉财报缓存（换挂载目录 / 重新导入财报后调用）
     * <p>
     * 缓存本体在 {@link FinanceSnapshot}：脚本按股票缓存整条时间线，
     * 换了财务库还留着旧的，选出来的结果就会跟画面对不上。
     * {@link FinanceFigures} 还按 (股票, 交易日) 缓存算好的指标值，也得一起作废，
     * 否则更新完财报再跑一遍，PE/PB 还是旧的。
     */
    public static synchronized void clearCache() {
        FinanceSnapshot.invalidate();
        FinanceFigures.invalidate();
        PriceFactor.invalidate();
    }

    /**
     * 财报库文件路径，用于界面提示；未挂载财务数据时返回空串。
     */
    public static String dbFilePath() {
        try {
            return FinanceDBManager.resolveDbFile(null).getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 去掉 sh/sz/bj 前缀与 .SH 这类后缀，得到 6 位代码
     */
    public static String plainCode(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj")) {
            text = text.substring(2);
        }
        // 兼容 600519.SH / 600519.sh 这类写法
        int dot = text.indexOf('.');
        if (dot > 0) {
            text = text.substring(0, dot);
        }
        return text;
    }

    // ------------------------------------------------------------ 内部方法

    private static List<String> candidateCodes(String stockCode) {
        List<String> codes = new ArrayList<>();
        String plain = plainCode(stockCode);
        if (plain.isEmpty()) {
            return codes;
        }
        // 库里的代码可能带市场前缀（tdx.finance.codePrefix=true），两种写法都查
        codes.add(plain);
        codes.add("sh" + plain);
        codes.add("sz" + plain);
        codes.add("bj" + plain);
        return codes;
    }

    private static FinanceDBManager openDb() {
        String dbName;
        try {
            dbName = FinanceFetchConfig.getInstance().dbName();
        } catch (Exception e) {
            dbName = "finance";
        }
        return new FinanceDBManager(dbName);
    }

    /**
     * 财报库文件是否存在：不存在时直接返回空数据，避免连库时新建一个空文件
     */
    private static boolean dbFileExists() {
        try {
            String dbName;
            try {
                dbName = FinanceFetchConfig.getInstance().dbName();
            } catch (Exception e) {
                dbName = "finance";
            }
            File file = FinanceDBManager.resolveDbFile(dbName);
            return file.isFile() && file.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * fields 列只有在按 python meta 导入过财报后才存在
     */
    private static boolean hasFieldsColumn(FinanceDBManager db) throws DBException {
        return db.existingColumns(FinanceDBManager.TABLE_FINANCE).contains("fields");
    }
}
