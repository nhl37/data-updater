package updater.holder;

import updater.db.FinanceDBManager;
import updater.db.VaultDb;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流通大股东研究的本地库（默认 {@code data/holder.db}，与财报库同目录）。
 *
 * <p>为什么单独一个库、不塞进 {@code f10.db}：这里存的是<b>全市场按季度平铺</b>的十万级行
 * （一个季度约 5 万条，两年就是 40 万条），需要建自己的索引；f10.db 里放的是「按股票缓存的原始 JSON」，
 * 两者更新节奏也不同（F10 缓存按天过期，这里按季度落库），混在一起查询会互相拖慢。</p>
 *
 * <p>三张表：</p>
 * <pre>
 *   T_Holder        股东持仓明细，主键(报告期, 代码, 排名)，一个季度一行一只股票十位股东
 *   T_HolderPeriod  已同步的季度与汇总（用来在界面上标「已同步 / 未同步」）
 *   T_HolderTag     用户给股东打的标签；T_HolderTagDef 是标签本身的定义（名字 + 颜色）
 * </pre>
 *
 * <p><b>明文库</b>：数据本身是公开信息（谁持有多少股），标签也只是分类记号不是私人备注，
 * 按「下载来的数据一律明文」的策略走 {@link VaultDb#openPlain}。这样用户想拿它去 Excel / pandas
 * 里自己算也不会被挡住。</p>
 *
 * <p>SQLite 只有一个连接、没有连接池：所有访问都必须先进 {@link #LOCK}，
 * 否则后台同步在写、界面在读时会撞上 "database is locked"。</p>
 */
public final class HolderStore {

    /** 所有数据库访问的公共锁：同步线程写、界面线程读，只能串行 */
    public static final Object LOCK = new Object();

    private static Connection connection;
    private static String path;

    public HolderStore() {
    }

    private static final String CREATE_HOLDER =
            "CREATE TABLE IF NOT EXISTS T_Holder("
                    + " REPORT_DATE INTEGER NOT NULL,"
                    + " CODE TEXT NOT NULL,"
                    + " STOCK_NAME TEXT,"
                    + " RANK INTEGER NOT NULL,"
                    + " HOLDER_NAME TEXT NOT NULL,"
                    + " HOLDER_KEY TEXT NOT NULL,"
                    + " HOLDER_TYPE TEXT,"
                    + " HOLDER_NATURE TEXT,"
                    + " CATEGORY INTEGER NOT NULL,"
                    + " HOLD_NUM INTEGER NOT NULL,"
                    + " HOLD_RATIO REAL NOT NULL,"
                    + " MARKET_CAP REAL NOT NULL,"
                    + " DIRECTION TEXT,"
                    + " CHANGE_RATIO REAL,"
                    + " HOLD_NUM_CHANGE INTEGER NOT NULL,"
                    + " PRIMARY KEY(REPORT_DATE, CODE, RANK))";

    /** 按报告期取市值榜 / 加仓榜时走这条；按股东跨季度聚合时走 HOLDER_KEY 那条 */
    private static final String[] HOLDER_INDEXES = {
            "CREATE INDEX IF NOT EXISTS IDX_HOLDER_DATE_CAP ON T_Holder(REPORT_DATE, MARKET_CAP)",
            "CREATE INDEX IF NOT EXISTS IDX_HOLDER_KEY_DATE ON T_Holder(HOLDER_KEY, REPORT_DATE)",
            "CREATE INDEX IF NOT EXISTS IDX_HOLDER_DATE_CODE ON T_Holder(REPORT_DATE, CODE)"
    };

    private static final String CREATE_PERIOD =
            "CREATE TABLE IF NOT EXISTS T_HolderPeriod("
                    + " REPORT_DATE INTEGER PRIMARY KEY,"
                    + " ROW_COUNT INTEGER NOT NULL,"
                    + " STOCK_COUNT INTEGER NOT NULL,"
                    + " TOTAL_CAP REAL NOT NULL,"
                    + " FETCHED_AT INTEGER NOT NULL)";

    private static final String CREATE_TAG =
            "CREATE TABLE IF NOT EXISTS T_HolderTag("
                    + " HOLDER_KEY TEXT NOT NULL,"
                    + " TAG TEXT NOT NULL,"
                    + " PRIMARY KEY(HOLDER_KEY, TAG))";

    /**
     * 分类规则版本。改了 {@link HolderCategory} 的关键词表就把这个数 +1：
     * 下次打开窗口会把库里所有行的分类重算一遍，老数据不会和代码打架。
     */
    private static final int CATEGORY_VERSION = 2;

    private static final String CREATE_META =
            "CREATE TABLE IF NOT EXISTS T_HolderMeta("
                    + " KEY TEXT PRIMARY KEY,"
                    + " VALUE INTEGER NOT NULL)";

    private static final String CREATE_TAG_DEF =
            "CREATE TABLE IF NOT EXISTS T_HolderTagDef("
                    + " TAG TEXT PRIMARY KEY,"
                    + " COLOR TEXT,"
                    + " CREATED_AT INTEGER NOT NULL)";

    /** 库文件路径：优先跟财报库同目录（挂载的 data 目录），没挂载时退回工作目录 data/ */
    public static String dbFilePath() {
        try {
            File finance = FinanceDBManager.resolveDbFile(null);
            File dir = finance.getParentFile();
            return new File(dir == null ? new File("data") : dir, "holder.db").getAbsolutePath();
        } catch (Exception e) {
            return "data/holder.db";
        }
    }

    private static Connection open() throws SQLException {
        String target = dbFilePath();
        if (connection != null) {
            try {
                if (!connection.isClosed() && target.equals(path)) {
                    return connection;
                }
                connection.close();
            } catch (SQLException ignored) {
                // 换库时关不掉旧连接不影响新连接
            }
        }
        File file = new File(target);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        Connection conn = VaultDb.openPlain(file);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute(CREATE_HOLDER);
            for (String sql : HOLDER_INDEXES) {
                stmt.execute(sql);
            }
            stmt.execute(CREATE_PERIOD);
            stmt.execute(CREATE_TAG);
            stmt.execute(CREATE_TAG_DEF);
            stmt.execute(CREATE_META);
        }
        connection = conn;
        path = target;
        upgradeCategories(conn);
        return conn;
    }

    /**
     * 分类规则升级：只在规则版本变化时跑一次，把老数据按新关键词重新归类。
     *
     * <p>分类是<b>写入时</b>算好落库的（查询时再算会让每次排序都多几十万次字符串匹配）。
     * 代价是规则一改，老数据还是旧分类 —— 看起来就像「明明加了关键词却没有生效」。
     * 用版本号把这次重算限定成「规则变了才做」，正常启动不受影响。</p>
     */
    private static void upgradeCategories(Connection conn) {
        try {
            int version = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT VALUE FROM T_HolderMeta WHERE KEY='CATEGORY_VERSION'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    version = rs.getInt(1);
                }
            }
            if (version >= CATEGORY_VERSION) {
                return;
            }
            List<Object[]> updates = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT REPORT_DATE, CODE, RANK, HOLDER_TYPE, HOLDER_NATURE, HOLDER_NAME, CATEGORY FROM T_Holder");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HolderCategory category = HolderCategory.classify(
                            rs.getString(4), rs.getString(5), rs.getString(6));
                    if (category.ordinal() != rs.getInt(7)) {
                        updates.add(new Object[]{rs.getInt(1), rs.getString(2), rs.getInt(3),
                                category.ordinal()});
                    }
                }
            }
            if (!updates.isEmpty()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE T_Holder SET CATEGORY=? WHERE REPORT_DATE=? AND CODE=? AND RANK=?")) {
                    for (Object[] row : updates) {
                        ps.setInt(1, (Integer) row[3]);
                        ps.setInt(2, (Integer) row[0]);
                        ps.setString(3, (String) row[1]);
                        ps.setInt(4, (Integer) row[2]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(true);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO T_HolderMeta(KEY,VALUE) VALUES('CATEGORY_VERSION',?)")) {
                ps.setInt(1, CATEGORY_VERSION);
                ps.executeUpdate();
            }
            if (!updates.isEmpty()) {
                updater.log.Log.out("股东库分类已按新规则重算：" + updates.size() + " 行");
            }
        } catch (Exception e) {
            // 重算失败不能挡住窗口：下次打开还会再试
            updater.log.Log.out("warn ##### 股东分类重算失败：" + e.getMessage());
        }
    }

    /** 拿连接（首次调用会建表）。调用方必须自己进 {@link #LOCK} */
    public static Connection connection() throws SQLException {
        return open();
    }

    /** 股东名归一化：去掉所有空白并转小写，跨季度聚合与打标签都用它当主键 */
    public static String key(String holderName) {
        if (holderName == null) {
            return "";
        }
        String text = holderName.replace('\u3000', ' ').trim();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // ===================== 写入 =====================

    /** 删除某个报告期的全部数据（重新同步前先清空，避免残留旧股东） */
    public void deletePeriod(int reportDate) throws SQLException {
        synchronized (LOCK) {
            Connection conn = connection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM T_Holder WHERE REPORT_DATE=?")) {
                ps.setInt(1, reportDate);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM T_HolderPeriod WHERE REPORT_DATE=?")) {
                ps.setInt(1, reportDate);
                ps.executeUpdate();
            }
        }
    }

    /** 一批写入（500 条一个事务），同步时按页调用 */
    public void insert(int reportDate, List<HolderRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Connection conn = connection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO T_Holder(REPORT_DATE,CODE,STOCK_NAME,RANK,HOLDER_NAME,"
                            + "HOLDER_KEY,HOLDER_TYPE,HOLDER_NATURE,CATEGORY,HOLD_NUM,HOLD_RATIO,"
                            + "MARKET_CAP,DIRECTION,CHANGE_RATIO,HOLD_NUM_CHANGE)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (HolderRow row : rows) {
                    ps.setInt(1, reportDate);
                    ps.setString(2, row.code());
                    ps.setString(3, row.stockName());
                    ps.setInt(4, row.rank());
                    ps.setString(5, row.holderName());
                    ps.setString(6, row.holderKey());
                    ps.setString(7, row.holderType());
                    ps.setString(8, row.nature());
                    ps.setInt(9, row.category().ordinal());
                    ps.setLong(10, row.holdNum());
                    ps.setDouble(11, row.holdRatio());
                    ps.setDouble(12, row.marketCap());
                    ps.setString(13, row.direction());
                    if (row.changeRatio() == null) {
                        ps.setNull(14, java.sql.Types.REAL);
                    } else {
                        ps.setDouble(14, row.changeRatio());
                    }
                    ps.setLong(15, row.holdNumChange());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 同步完成后写这条季度的汇总，界面用它区分「已同步」和「空季度」 */
    public void finishPeriod(int reportDate) throws SQLException {
        synchronized (LOCK) {
            Connection conn = connection();
            int rows;
            int stocks;
            double cap;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*), COUNT(DISTINCT CODE), SUM(MARKET_CAP) FROM T_Holder WHERE REPORT_DATE=?")) {
                ps.setInt(1, reportDate);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    rows = rs.getInt(1);
                    stocks = rs.getInt(2);
                    cap = rs.getDouble(3);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO T_HolderPeriod(REPORT_DATE,ROW_COUNT,STOCK_COUNT,TOTAL_CAP,FETCHED_AT)"
                            + " VALUES(?,?,?,?,?)")) {
                ps.setInt(1, reportDate);
                ps.setInt(2, rows);
                ps.setInt(3, stocks);
                ps.setDouble(4, cap);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    // ===================== 读取：季度 =====================

    /** 已同步的季度，按时间升序 */
    public List<Integer> periods() {
        List<Integer> list = new ArrayList<>();
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT REPORT_DATE FROM T_HolderPeriod ORDER BY REPORT_DATE")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getInt(1));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取股东库季度失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    /** 某季度已入库的行数；没同步过返回 0 */
    public int rowCount(int reportDate) {
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT ROW_COUNT FROM T_HolderPeriod WHERE REPORT_DATE=?")) {
                ps.setInt(1, reportDate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 某季度覆盖的股票数；查不到返回 0 */
    public int stockCount(int reportDate) {
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT COUNT(DISTINCT CODE) FROM T_Holder WHERE REPORT_DATE=?")) {
                ps.setInt(1, reportDate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ===================== 读取：标签 =====================

    /** 标签定义：名字 → 颜色（#RRGGBB） */
    public Map<String, String> tagDefs() {
        Map<String, String> map = new LinkedHashMap<>();
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT TAG, COLOR FROM T_HolderTagDef ORDER BY CREATED_AT, TAG")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取标签失败：" + e.getMessage(), e);
            }
        }
        return map;
    }

    /** 每个标签下有多少位股东（标签管理页显示「12 位」） */
    public Map<String, Integer> tagCounts() {
        Map<String, Integer> map = new HashMap<>();
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT TAG, COUNT(*) FROM T_HolderTag GROUP BY TAG")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getInt(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计标签失败：" + e.getMessage(), e);
            }
        }
        return map;
    }

    /** 新建标签（已存在则只更新颜色） */
    public void putTagDef(String tag, String color) throws SQLException {
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "INSERT INTO T_HolderTagDef(TAG,COLOR,CREATED_AT) VALUES(?,?,?)"
                            + " ON CONFLICT(TAG) DO UPDATE SET COLOR=excluded.COLOR")) {
                ps.setString(1, tag);
                ps.setString(2, color);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    /** 删除标签定义，同时清掉所有股东身上的这个标签 */
    public void deleteTag(String tag) throws SQLException {
        synchronized (LOCK) {
            Connection conn = connection();
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM T_HolderTag WHERE TAG=?")) {
                ps.setString(1, tag);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM T_HolderTagDef WHERE TAG=?")) {
                ps.setString(1, tag);
                ps.executeUpdate();
            }
        }
    }

    /**
     * 标签改名：把股东身上的旧标签迁到新名字下，再删掉旧定义。
     *
     * <p>先 {@code INSERT OR IGNORE} 再删旧名字：如果某些股东本来就有同名新标签，
     * 主键会挡住重复插入，不会因为改名把标签算两遍。</p>
     */
    public void renameTag(String oldTag, String newTag) throws SQLException {
        if (oldTag == null || newTag == null || oldTag.equals(newTag)) {
            return;
        }
        synchronized (LOCK) {
            Connection conn = connection();
            conn.setAutoCommit(false);
            try {
                String color = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COLOR FROM T_HolderTagDef WHERE TAG=?")) {
                    ps.setString(1, oldTag);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            color = rs.getString(1);
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO T_HolderTag(HOLDER_KEY,TAG) SELECT HOLDER_KEY,? FROM T_HolderTag WHERE TAG=?")) {
                    ps.setString(1, newTag);
                    ps.setString(2, oldTag);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM T_HolderTag WHERE TAG=?")) {
                    ps.setString(1, oldTag);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM T_HolderTagDef WHERE TAG=?")) {
                    ps.setString(1, oldTag);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO T_HolderTagDef(TAG,COLOR,CREATED_AT) VALUES(?,?,?)"
                                + " ON CONFLICT(TAG) DO UPDATE SET COLOR=excluded.COLOR")) {
                    ps.setString(1, newTag);
                    ps.setString(2, color);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 覆盖写某位股东的标签集合 */
    public void setTags(String holderKey, Collection<String> tags) throws SQLException {
        synchronized (LOCK) {
            Connection conn = connection();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM T_HolderTag WHERE HOLDER_KEY=?")) {
                    ps.setString(1, holderKey);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO T_HolderTag(HOLDER_KEY,TAG) VALUES(?,?)")) {
                    for (String tag : tags) {
                        if (tag == null || tag.isEmpty()) {
                            continue;
                        }
                        ps.setString(1, holderKey);
                        ps.setString(2, tag);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 某位股东的标签 */
    public List<String> tagsOf(String holderKey) {
        List<String> list = new ArrayList<>();
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT TAG FROM T_HolderTag WHERE HOLDER_KEY=? ORDER BY TAG")) {
                ps.setString(1, holderKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取股东标签失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    /** 一次性取一批股东的标签，避免表格每一行查一次库 */
    public Map<String, List<String>> tagsOf(Collection<String> holderKeys) {
        Map<String, List<String>> map = new HashMap<>();
        if (holderKeys == null || holderKeys.isEmpty()) {
            return map;
        }
        List<String> keys = new ArrayList<>(holderKeys);
        synchronized (LOCK) {
            int from = 0;
            while (from < keys.size()) {
                int to = Math.min(from + 400, keys.size());
                List<String> chunk = keys.subList(from, to);
                StringBuilder sql = new StringBuilder(
                        "SELECT HOLDER_KEY, TAG FROM T_HolderTag WHERE HOLDER_KEY IN (");
                for (int i = 0; i < chunk.size(); i++) {
                    sql.append(i == 0 ? "?" : ",?");
                }
                sql.append(")");
                try (PreparedStatement ps = connection().prepareStatement(sql.toString())) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("批量读取标签失败：" + e.getMessage(), e);
                }
                from = to;
            }
        }
        return map;
    }

    /** 带某个标签的股东 key 集合（用于「包含 / 不包含」过滤） */
    public List<String> holderKeysWithTag(String tag) {
        List<String> list = new ArrayList<>();
        synchronized (LOCK) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT HOLDER_KEY FROM T_HolderTag WHERE TAG=?")) {
                ps.setString(1, tag);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("按标签查股东失败：" + e.getMessage(), e);
            }
        }
        return list;
    }
}
