package updater.db;

import updater.mount.MountConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 财报数据库管理器
 *
 * 数据库文件位置：只认「挂载数据」画面里挂的财务库文件（mount.properties）。
 * 未挂载时没有任何财务数据可读，不会回落到程序目录下的 data/finance.db。
 *
 * 明细表 T_D_Finance：code + report_date 为主键，其余财务字段按解析结果动态建列
 * 日志表 T_Finance_Import：记录每个报告期的处理状态(DONE 表示已处理完成)
 */
public class FinanceDBManager {

    public static final String TABLE_FINANCE = "T_D_Finance";
    public static final String TABLE_IMPORT_LOG = "T_Finance_Import";

    public static final String COL_CODE = "code";
    public static final String COL_REPORT_DATE = "report_date";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private static final int BATCH_SIZE = 500;
    private static final int LOG_EVERY = 2000;

    private Connection conn;

    /**
     * 挂载模式下全程序只有一份财务库，文件由 mount.properties 唯一指定；
     * 库名参数仅为兼容旧调用保留。
     */
    public FinanceDBManager(String dbName) {
    }

    /**
     * 解析财报库文件：直接返回挂载的财务库文件。
     *
     * <p>未挂载时抛出 {@link DBException}，绝不再按 config.properties 的
     * tdx.finance.* 或程序目录拼一个默认路径。</p>
     *
     * @param dbName 仅兼容旧调用，实际库文件由挂载决定
     */
    public static File resolveDbFile(String dbName) throws DBException {
        String mounted = MountConfig.currentFinanceDbFile();
        if (mounted.isEmpty()) {
            throw new DBException(MountConfig.NO_FINANCE_DATA_MESSAGE);
        }
        File file = new File(mounted).getAbsoluteFile();
        if (!file.isFile()) {
            throw new DBException("挂载的财务库文件不存在: " + file.getAbsolutePath());
        }
        return file;
    }

    /**
     * 获取(懒加载)数据库连接：连挂载的财务库文件
     */
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

        File dbFile = resolveDbFile(null);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new DBException("无法创建财报数据库目录: " + parent.getAbsolutePath());
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("未找到 org.sqlite.JDBC，尝试通过 SPI 加载驱动: " + e.getMessage());
        }

        try {
            // 财报是下载回来的明文数据。换台电脑、或者想用 pandas 自己算都不该被挡住，
            // 而且加密也防不住 —— 用户手里本来就有钥匙。老密文库由 openPlain 一次性转过来。
            conn = VaultDb.openPlain(dbFile);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            System.out.println("财报数据库文件: " + dbFile.getAbsolutePath());
            return conn;
        } catch (SQLException e) {
            throw new DBException("连接财报数据库失败: " + dbFile.getAbsolutePath(), e);
        }
    }

    /**
     * 财报库文件绝对路径
     */
    public String getDbPath() throws DBException {
        return resolveDbFile(null).getAbsolutePath();
    }

    /**
     * 财报库所在目录绝对路径
     */
    public String getDbDirPath() throws DBException {
        File dbFile = resolveDbFile(null);
        File parent = dbFile.getParentFile();
        return parent == null ? null : parent.getAbsolutePath();
    }

    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("关闭财报数据库连接失败: " + e.getMessage());
            } finally {
                conn = null;
            }
        }
    }

    /**
     * 初始化：创建明细表与导入日志表
     */
    public void init() throws DBException {
        execute("CREATE TABLE IF NOT EXISTS " + TABLE_FINANCE + " ("
                + COL_CODE + " TEXT NOT NULL, "
                + COL_REPORT_DATE + " TEXT NOT NULL, "
                + "PRIMARY KEY (" + COL_CODE + ", " + COL_REPORT_DATE + ")"
                + ")");

        execute("CREATE TABLE IF NOT EXISTS " + TABLE_IMPORT_LOG + " ("
                + "report_date TEXT NOT NULL PRIMARY KEY, "
                + "source_file TEXT, "
                + "file_size INTEGER DEFAULT 0, "
                + "row_count INTEGER DEFAULT 0, "
                + "status TEXT, "
                + "start_time TEXT, "
                + "finish_time TEXT, "
                + "message TEXT"
                + ")");

        execute("CREATE INDEX IF NOT EXISTS idx_finance_report_date ON "
                + TABLE_FINANCE + "(" + COL_REPORT_DATE + ")");
    }

    /**
     * 判断该报告期是否已经处理完成(用于跳过重复导入)
     */
    public boolean isReportDateDone(String reportDate) throws DBException {
        String value = trim(reportDate);
        if (value.isEmpty()) {
            return false;
        }
        String sql = "SELECT status FROM " + TABLE_IMPORT_LOG + " WHERE report_date = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, value);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return STATUS_DONE.equalsIgnoreCase(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new DBException("查询财报导入状态失败: " + value, e);
        }
        return false;
    }

    /**
     * 统计该报告期已有的记录数
     */
    public long countFinanceByReportDate(String reportDate) throws DBException {
        String value = trim(reportDate);
        if (value.isEmpty()) {
            return 0L;
        }
        String sql = "SELECT COUNT(*) FROM " + TABLE_FINANCE + " WHERE " + COL_REPORT_DATE + " = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, value);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DBException("统计财报记录失败: " + value, e);
        }
    }
    /**
     * 判断该报告期是否已有财报数据(用于"是否覆盖"提示)
     */
    public boolean hasFinanceData(String reportDate) throws DBException {
        return countFinanceByReportDate(reportDate) > 0;
    }
    /**
     * 删除指定报告期的全部财报数据(覆盖导入前使用)
     *
     * @return 删除的记录数
     */
    public int deleteByReportDate(String reportDate) throws DBException {
        String value = trim(reportDate);
        if (value.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM " + TABLE_FINANCE + " WHERE " + COL_REPORT_DATE + " = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, value);
            int deleted = pstmt.executeUpdate();
            System.out.println("已删除报告期 " + value + " 的旧财报数据: " + deleted + " 条");
            return deleted;
        } catch (SQLException e) {
            throw new DBException("删除财报数据失败: " + value, e);
        }
    }

    /**
     * 获取已有列名(小写)
     */
    public Set<String> existingColumns(String tableName) throws DBException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    columns.add(name.toLowerCase());
                }
            }
        } catch (SQLException e) {
            throw new DBException("读取表结构失败: " + tableName, e);
        }
        return columns;
    }

    /**
     * 按 meta 中的列与类型补建列
     */
    public void ensureColumns(List<String> columns, List<String> types) throws DBException {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Set<String> exist = existingColumns(TABLE_FINANCE);
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (!isSafeName(column)) {
                System.err.println("跳过非法列名: " + column);
                continue;
            }
            if (COL_CODE.equalsIgnoreCase(column) || COL_REPORT_DATE.equalsIgnoreCase(column)) {
                continue;
            }
            if (exist.contains(column.toLowerCase())) {
                continue;
            }
            String type = "TEXT";
            if (types != null && i < types.size() && "REAL".equalsIgnoreCase(types.get(i))) {
                type = "REAL";
            }
            execute("ALTER TABLE " + TABLE_FINANCE + " ADD COLUMN \"" + column + "\" " + type);
            exist.add(column.toLowerCase());
            System.out.println("新增财报列: " + column + " " + type);
        }
    }

    /**
     * 记录导入开始(状态置为 RUNNING)
     */
    public void startImport(String reportDate, String sourceFile, long fileSize) throws DBException {
        String date = trim(reportDate);
        String now = now();
        String updateSql = "UPDATE " + TABLE_IMPORT_LOG
                + " SET source_file=?, file_size=?, row_count=0, status=?, start_time=?, finish_time=NULL, message=NULL"
                + " WHERE report_date=?";
        int affected = update(updateSql, sourceFile, fileSize, STATUS_RUNNING, now, date);
        if (affected == 0) {
            update("INSERT INTO " + TABLE_IMPORT_LOG
                            + " (report_date, source_file, file_size, row_count, status, start_time)"
                            + " VALUES (?, ?, ?, 0, ?, ?)",
                    date, sourceFile, fileSize, STATUS_RUNNING, now);
        }
    }

    /**
     * 记录导入完成(状态置为 DONE)
     */
    public void finishImport(String reportDate, long rowCount, String message) throws DBException {
        String date = trim(reportDate);
        String now = now();
        String updateSql = "UPDATE " + TABLE_IMPORT_LOG
                + " SET row_count=?, status=?, finish_time=?, message=? WHERE report_date=?";
        int affected = update(updateSql, rowCount, STATUS_DONE, now, message, date);
        if (affected == 0) {
            update("INSERT INTO " + TABLE_IMPORT_LOG
                            + " (report_date, row_count, status, finish_time, message)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    date, rowCount, STATUS_DONE, now, message);
        }
    }

    /**
     * 记录导入失败(状态置为 FAILED)
     */
    public void failImport(String reportDate, String message) throws DBException {
        String date = trim(reportDate);
        if (date.isEmpty()) {
            return;
        }
        String now = now();
        String updateSql = "UPDATE " + TABLE_IMPORT_LOG
                + " SET status=?, finish_time=?, message=? WHERE report_date=?";
        int affected = update(updateSql, STATUS_FAILED, now, message, date);
        if (affected == 0) {
            update("INSERT INTO " + TABLE_IMPORT_LOG
                            + " (report_date, row_count, status, finish_time, message)"
                            + " VALUES (?, 0, ?, ?, ?)",
                    date, STATUS_FAILED, now, message);
        }
    }

    /**
     * 查询导入日志摘要，用于控制台展示
     */
    public List<String> queryImportLogSummary(int limit) throws DBException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT report_date, source_file, row_count, status, finish_time FROM "
                + TABLE_IMPORT_LOG + " ORDER BY report_date DESC LIMIT ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(String.format("%s | %s | rows=%d | %s | %s",
                            rs.getString(1),
                            nullToEmpty(rs.getString(2)),
                            rs.getLong(3),
                            nullToEmpty(rs.getString(4)),
                            nullToEmpty(rs.getString(5))));
                }
            }
        } catch (SQLException e) {
            throw new DBException("查询财报导入日志失败", e);
        }
        return result;
    }

    /**
     * 读取 CSV 并批量写入财报明细表
     *
     * @param csvFile    CSV 文件(第一行为表头)
     * @param columns    meta 中声明的列顺序(须包含 code 与 report_date)
     * @param codePrefix 是否把 6 位代码补成带市场前缀
     * @return 写入的记录数
     */
    public int importCsv(Path csvFile, List<String> columns, boolean codePrefix)
            throws DBException, IOException {
        return importCsv(csvFile, columns, codePrefix, null);
    }

    /**
     * 只导入指定代码的行（{@code null} 或空集表示整季全导）。
     *
     * <p>补单只财报用：整季 CSV 已经躺在本地时，读一遍但只写那几行——
     * 其它股票的数据一个字节都不动，也不会先 {@code deleteByReportDate} 整季重写。</p>
     *
     * @param onlyCodes 只写这些代码；带不带市场前缀都行，内部按 {@code codePrefix} 归一
     */
    public int importCsv(Path csvFile, List<String> columns, boolean codePrefix, Set<String> onlyCodes)
            throws DBException, IOException {

        if (csvFile == null || !Files.isRegularFile(csvFile)) {
            throw new IOException("CSV 文件不存在: " + csvFile);
        }

        Set<String> filter = null;
        if (onlyCodes != null && !onlyCodes.isEmpty()) {
            filter = new LinkedHashSet<String>();
            for (String code : onlyCodes) {
                if (code == null || code.trim().isEmpty()) {
                    continue;
                }
                filter.add(codePrefix ? normalizeStockCode(code.trim()) : code.trim());
            }
        }

        List<String> safeColumns = new ArrayList<>();
        for (String column : columns) {
            if (isSafeName(column)) {
                safeColumns.add(column);
            }
        }
        if (!containsIgnoreCase(safeColumns, COL_CODE)
                || !containsIgnoreCase(safeColumns, COL_REPORT_DATE)) {
            throw new DBException("CSV 列定义中缺少 " + COL_CODE + " 或 " + COL_REPORT_DATE);
        }

        StringBuilder sqlBuilder = new StringBuilder("INSERT OR REPLACE INTO ")
                .append(TABLE_FINANCE).append(" (");
        for (int i = 0; i < safeColumns.size(); i++) {
            if (i > 0) {
                sqlBuilder.append(", ");
            }
            sqlBuilder.append("\"").append(safeColumns.get(i)).append("\"");
        }
        sqlBuilder.append(") VALUES (");
        for (int i = 0; i < safeColumns.size(); i++) {
            if (i > 0) {
                sqlBuilder.append(", ");
            }
            sqlBuilder.append("?");
        }
        sqlBuilder.append(")");
        String insertSql = sqlBuilder.toString();

        Connection connection = getConnection();
        boolean autoCommit = false;
        try {
            autoCommit = connection.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        int total = 0;

        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }
            List<String> headerColumns = parseCsvLine(headerLine);

            int[] indexMap = new int[safeColumns.size()];
            for (int i = 0; i < safeColumns.size(); i++) {
                indexMap[i] = indexOfIgnoreCase(headerColumns, safeColumns.get(i));
            }
            if (indexOfIgnoreCase(headerColumns, COL_CODE) < 0
                    || indexOfIgnoreCase(headerColumns, COL_REPORT_DATE) < 0) {
                throw new DBException("CSV 表头缺少 " + COL_CODE + " 或 " + COL_REPORT_DATE
                        + ": " + headerColumns);
            }

            connection.setAutoCommit(false);
            try (PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
                String line;
                int batchCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    List<String> values = parseCsvLine(line);
                    String rowCode = null;
                    for (int i = 0; i < indexMap.length; i++) {
                        int index = indexMap[i];
                        String value = (index >= 0 && index < values.size()) ? values.get(index) : null;
                        value = normalizeValue(value);
                        if (COL_CODE.equalsIgnoreCase(safeColumns.get(i)) && codePrefix && value != null) {
                            value = normalizeStockCode(value);
                        }
                        if (COL_CODE.equalsIgnoreCase(safeColumns.get(i))) {
                            rowCode = value;
                        }
                        if (value == null) {
                            pstmt.setNull(i + 1, java.sql.Types.VARCHAR);
                        } else {
                            pstmt.setString(i + 1, value);
                        }
                    }
                    if (filter != null && (rowCode == null || !filter.contains(rowCode))) {
                        continue;
                    }
                    pstmt.addBatch();
                    batchCount++;
                    total++;
                    if (batchCount >= BATCH_SIZE) {
                        pstmt.executeBatch();
                        batchCount = 0;
                    }
                    if (total % LOG_EVERY == 0) {
                        System.out.println("已导入 " + total + " 条财报记录...");
                    }
                }
                if (batchCount > 0) {
                    pstmt.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new DBException("导入财报数据失败: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DBException("恢复自动提交失败: " + e.getMessage(), e);
        }

        return total;
    }

    /**
     * 某报告期已有的全部股票代码（带市场前缀，如 {@code sh600000}）。
     *
     * <p>用途：查缺补漏算「这一季还差哪几只」。跟「应有清单」求差集就得到要补的代码，
     * 补的时候按代码单条 {@code INSERT OR REPLACE}，不碰其它股票。</p>
     */
    public Set<String> codesByReportDate(String reportDate) throws DBException {
        Set<String> out = new LinkedHashSet<String>();
        String value = trim(reportDate);
        if (value.isEmpty()) {
            return out;
        }
        String sql = "SELECT " + COL_CODE + " FROM " + TABLE_FINANCE
                + " WHERE " + COL_REPORT_DATE + " = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, value);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    if (code != null && !code.trim().isEmpty()) {
                        out.add(code.trim());
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBException("读取财报代码清单失败: " + value, e);
        }
        return out;
    }

    /**
     * {@code fromReportDate}（含）以来的全部股票代码并集（带市场前缀）。
     *
     * <p>这是「应有清单」的来源，不用本地日线库那份代码表：财报库里从来没有出现过的代码
     * （次新、未披露、退市）本来就不该有财报，拿日线库求差只会得到一堆补不上的假缺失。</p>
     */
    public Set<String> codesSince(String fromReportDate) throws DBException {
        Set<String> out = new LinkedHashSet<String>();
        String value = trim(fromReportDate);
        String sql = "SELECT DISTINCT " + COL_CODE + " FROM " + TABLE_FINANCE
                + (value.isEmpty() ? "" : " WHERE " + COL_REPORT_DATE + " >= ?");
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            if (!value.isEmpty()) {
                pstmt.setString(1, value);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    if (code != null && !code.trim().isEmpty()) {
                        out.add(code.trim());
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBException("读取财报代码清单失败: " + value, e);
        }
        return out;
    }

    /**
     * 6 位代码补市场前缀：6/9/5->sh, 0/2/3->sz, 4/8->bj
     */
    public static String normalizeStockCode(String code) {
        if (code == null) {
            return null;
        }
        String text = code.trim();
        if (text.isEmpty() || text.length() > 6) {
            return text;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj")) {
            return text;
        }
        char head = text.charAt(0);
        String prefix;
        if (head == '6' || head == '9' || head == '5') {
            prefix = "sh";
        } else if (head == '0' || head == '2' || head == '3') {
            prefix = "sz";
        } else if (head == '4' || head == '8') {
            prefix = "bj";
        } else {
            return text;
        }
        return prefix + text;
    }

    /**
     * 极简 CSV 行解析(支持双引号转义)
     */
    public static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }
        StringBuilder buffer = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        buffer.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    buffer.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                result.add(buffer.toString());
                buffer.setLength(0);
            } else {
                buffer.append(ch);
            }
        }
        result.add(buffer.toString());
        return result;
    }

    private void execute(String sql) throws DBException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new DBException("执行SQL失败: " + sql, e);
        }
    }

    private int update(String sql, Object... params) throws DBException {
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            bind(pstmt, params);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DBException("执行SQL失败: " + sql, e);
        }
    }

    private void bind(PreparedStatement pstmt, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value == null) {
                pstmt.setNull(i + 1, java.sql.Types.NULL);
            } else if (value instanceof Integer) {
                pstmt.setInt(i + 1, (Integer) value);
            } else if (value instanceof Long) {
                pstmt.setLong(i + 1, (Long) value);
            } else {
                pstmt.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        return indexOfIgnoreCase(values, target) >= 0;
    }

    private static int indexOfIgnoreCase(List<String> values, String target) {
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value != null && value.trim().equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSafeName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || "nan".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}