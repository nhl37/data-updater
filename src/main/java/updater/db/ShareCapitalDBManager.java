package updater.db;

import updater.fetchdata.tdx.finance.FinanceFetchConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股本 / 股东数据表（T_D_ShareCapital）的读写入口。
 * <p>
 * 表就在挂载的财务库里，和 T_D_Finance 同一个文件：
 * 这两份数据是同一批「更新财报」动作产出的，放一起便于备份与迁移，
 * 也避免再维护一个独立的库文件。
 * <p>
 * 主键 (code, report_date)。同一天可能有两个来源的数据（财报字段 / akshare），
 * 写入时按字段合并而不是整行覆盖，否则后写的会把先写的清空。
 */
public final class ShareCapitalDBManager {

    public static final String TABLE = "T_D_ShareCapital";

    public static final String COL_CODE = "code";
    public static final String COL_DATE = "report_date";

    private static final String[] DATA_COLUMNS = {
            "total_shares", "float_a_shares", "restricted_a_shares",
            "top10_holder_shares", "top10_holder_ratio", "top1_holder_shares",
            "holder_count", "free_float_shares",
    };
    private static final String[] TEXT_COLUMNS = {"change_reason", "ann_date", "source"};

    private static final int BATCH_SIZE = 500;

    private Connection conn;

    public ShareCapitalDBManager() {
    }

    // ==================== 连接与建表 ====================

    /**
     * 库文件：与财报库同一个（由「挂载数据」指定）
     */
    public static File resolveDbFile() throws DBException {
        String dbName = "finance";
        try {
            dbName = FinanceFetchConfig.getInstance().dbName();
        } catch (Exception ignored) {
            // 配置读不到就用默认库名
        }
        return FinanceDBManager.resolveDbFile(dbName);
    }

    public String getDbPath() throws DBException {
        return resolveDbFile().getAbsolutePath();
    }

    public Connection getConnection() throws DBException {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    return conn;
                }
            } catch (SQLException e) {
                conn = null;
            }
        }
        File dbFile = resolveDbFile();
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new DBException("无法创建股本数据库目录: " + parent.getAbsolutePath());
        }
        try {
            // 股本是下载来的数据，明文（与财报、名称库同口径）
            conn = VaultDb.openPlain(dbFile);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            return conn;
        } catch (SQLException e) {
            throw new DBException("连接股本数据库失败: " + dbFile.getAbsolutePath(), e);
        }
    }

    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("关闭股本数据库连接失败: " + e.getMessage());
            } finally {
                conn = null;
            }
        }
    }

    /**
     * 建表 + 建索引（幂等，可以每次打开都调）
     */
    public void ensureTable() throws DBException {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_CODE + " TEXT NOT NULL, "
                + COL_DATE + " TEXT NOT NULL, "
                + "total_shares REAL, "
                + "float_a_shares REAL, "
                + "restricted_a_shares REAL, "
                + "top10_holder_shares REAL, "
                + "top10_holder_ratio REAL, "
                + "top1_holder_shares REAL, "
                + "holder_count REAL, "
                + "free_float_shares REAL, "
                + "change_reason TEXT, "
                + "ann_date TEXT, "
                + "source TEXT, "
                + "update_time TEXT, "
                + "PRIMARY KEY (" + COL_CODE + ", " + COL_DATE + "))";
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(sql);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_share_capital_code_date ON "
                    + TABLE + "(" + COL_CODE + ", " + COL_DATE + ")");
        } catch (SQLException e) {
            throw new DBException("创建股本数据表失败", e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 批量写入，按 (code, report_date) 合并：
     * 已存在的行只补空字段，不覆盖已有值——两个来源各自只带一部分列，
     * 整行 REPLACE 会把对方写的流通股冲成 NULL。
     *
     * @return 实际写入（新增 + 更新）的行数
     */
    public int upsertAll(List<ShareCapital> rows) throws DBException {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        ensureTable();
        // 先按股票把已有数据读出来，在内存里合并，再整行写回
        Map<String, Map<String, ShareCapital>> existing = new HashMap<>();
        for (String code : distinctCodes(rows)) {
            existing.put(code, loadByCode(code));
        }
        List<ShareCapital> merged = new ArrayList<>(rows.size());
        for (ShareCapital row : rows) {
            if (row == null || row.code == null || row.code.isEmpty()
                    || row.reportDate == null || row.reportDate.isEmpty()) {
                continue;
            }
            Map<String, ShareCapital> byDate = existing.get(row.code);
            ShareCapital old = byDate == null ? null : byDate.get(row.reportDate);
            if (old == null) {
                merged.add(row);
            } else {
                // 以已有行为底，用新行补齐/更新有值的列
                old.mergeFrom(row);
                merged.add(old);
            }
        }
        if (merged.isEmpty()) {
            return 0;
        }

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO ").append(TABLE)
                .append(" (").append(COL_CODE).append(',').append(COL_DATE);
        for (String col : DATA_COLUMNS) {
            sql.append(',').append(col);
        }
        for (String col : TEXT_COLUMNS) {
            sql.append(',').append(col);
        }
        sql.append(",update_time) VALUES (?,?");
        for (int i = 0; i < DATA_COLUMNS.length + TEXT_COLUMNS.length + 1; i++) {
            sql.append(",?");
        }
        sql.append(')');

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql.toString())) {
            getConnection().setAutoCommit(false);
            int count = 0;
            for (ShareCapital row : merged) {
                int i = 1;
                pstmt.setString(i++, row.code);
                pstmt.setString(i++, row.reportDate);
                setDouble(pstmt, i++, row.totalShares);
                setDouble(pstmt, i++, row.floatShares);
                setDouble(pstmt, i++, row.restrictedShares);
                setDouble(pstmt, i++, row.top10HolderShares);
                setDouble(pstmt, i++, row.top10HolderRatio);
                setDouble(pstmt, i++, row.top1HolderShares);
                setDouble(pstmt, i++, row.holderCount);
                setDouble(pstmt, i++, row.freeFloatShares());
                pstmt.setString(i++, row.changeReason);
                pstmt.setString(i++, row.annDate);
                pstmt.setString(i++, row.source);
                pstmt.setString(i, time);
                pstmt.addBatch();
                count++;
                if (count % BATCH_SIZE == 0) {
                    pstmt.executeBatch();
                }
            }
            pstmt.executeBatch();
            getConnection().commit();
            return count;
        } catch (SQLException e) {
            try {
                getConnection().rollback();
            } catch (SQLException ignore) {
                // 回滚失败不影响抛出原始异常
            }
            throw new DBException("写入股本数据失败", e);
        } finally {
            try {
                getConnection().setAutoCommit(true);
            } catch (SQLException ignore) {
                // 连接已关闭时忽略
            }
        }
    }

    private static void setDouble(PreparedStatement pstmt, int index, Double value) throws SQLException {
        if (value == null || value.isNaN()) {
            pstmt.setNull(index, java.sql.Types.REAL);
        } else {
            pstmt.setDouble(index, value);
        }
    }

    private static List<String> distinctCodes(List<ShareCapital> rows) {
        List<String> codes = new ArrayList<>();
        for (ShareCapital row : rows) {
            if (row != null && row.code != null && !codes.contains(row.code)) {
                codes.add(row.code);
            }
        }
        return codes;
    }

    // ==================== 查询 ====================

    /**
     * 读取一只股票的全部股本时间线（按日期升序）
     */
    public List<ShareCapital> loadList(String code) throws DBException {
        Map<String, ShareCapital> map = loadByCode(code);
        return new ArrayList<>(map.values());
    }

    private Map<String, ShareCapital> loadByCode(String code) throws DBException {
        Map<String, ShareCapital> result = new LinkedHashMap<>();
        if (code == null || code.isEmpty()) {
            return result;
        }
        String sql = "SELECT " + COL_CODE + "," + COL_DATE + ","
                + String.join(",", DATA_COLUMNS) + ","
                + String.join(",", TEXT_COLUMNS)
                + " FROM " + TABLE + " WHERE " + COL_CODE + " = ? ORDER BY " + COL_DATE;
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, code);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ShareCapital item = new ShareCapital(rs.getString(1), rs.getString(2));
                    int i = 3;
                    item.totalShares = readDouble(rs, i++);
                    item.floatShares = readDouble(rs, i++);
                    item.restrictedShares = readDouble(rs, i++);
                    item.top10HolderShares = readDouble(rs, i++);
                    item.top10HolderRatio = readDouble(rs, i++);
                    item.top1HolderShares = readDouble(rs, i++);
                    item.holderCount = readDouble(rs, i++);
                    readDouble(rs, i++); // free_float_shares 是派生列，不参与读取
                    item.changeReason = rs.getString(i++);
                    item.annDate = rs.getString(i++);
                    item.source = rs.getString(i);
                    result.put(item.reportDate, item);
                }
            }
            return result;
        } catch (SQLException e) {
            // 表还没建起来时（第一次用）当作没有数据，不要把 K 线图的加载整条链拖垮
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return result;
            }
            throw new DBException("读取股本数据失败: " + code, e);
        }
    }

    private static Double readDouble(ResultSet rs, int index) throws SQLException {
        double value = rs.getDouble(index);
        return rs.wasNull() ? null : value;
    }

    /**
     * 表里一共有多少行；表不存在时返回 0
     */
    public long count() throws DBException {
        return countWhere(null, null);
    }

    /**
     * 某个报告期上有股本数据的股票数
     */
    public long countByDate(String reportDate) throws DBException {
        return countWhere(COL_DATE, reportDate);
    }

    /**
     * 某个报告期上「有流通股」的股票数（只填了股东数据的行不算）
     */
    public long countShareByDate(String reportDate) throws DBException {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE " + COL_DATE + " = ?"
                + " AND float_a_shares IS NOT NULL";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, reportDate);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return 0L;
            }
            throw new DBException("统计股本数据失败: " + reportDate, e);
        }
    }

    private long countWhere(String column, String value) throws DBException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + (column == null ? "" : " WHERE " + column + " = ?");
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            if (column != null) {
                pstmt.setString(1, value);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return 0L;
            }
            throw new DBException("统计股本数据失败", e);
        }
    }

    /**
     * 表里最新的数据日期（yyyyMMdd），空表返回空串
     */
    public String latestDate() throws DBException {
        String sql = "SELECT MAX(" + COL_DATE + ") FROM " + TABLE;
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            String value = rs.next() ? rs.getString(1) : null;
            return value == null ? "" : value;
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return "";
            }
            throw new DBException("查询股本数据最新日期失败", e);
        }
    }

    /**
     * 数据来源统计，形如 {FIN=5579, CNINFO=3200, EM=900}
     */
    public Map<String, Long> countBySource() throws DBException {
        Map<String, Long> result = new LinkedHashMap<>();
        String sql = "SELECT source, COUNT(*) FROM " + TABLE + " GROUP BY source ORDER BY 2 DESC";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
            return result;
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return result;
            }
            throw new DBException("统计股本数据来源失败", e);
        }
    }

    private static boolean isMissingTable(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains("no such table");
    }
}
