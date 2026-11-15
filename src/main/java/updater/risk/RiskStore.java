package updater.risk;

import updater.db.FinanceDBManager;
import updater.db.VaultDb;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险与估值库（默认 {@code data/risk.db}，与财报库同目录、明文）。
 *
 * <p>三张主表：</p>
 * <pre>
 *   T_RiskEvent   风险事件：公告派生（source=ann）+ 解禁派生（source=lift）
 *   T_Lift        限售解禁明细，用来排「未来 N 个月解禁压力」
 *   T_Valuation   估值快照：收盘价 / 总市值 / 流通市值 / PE(TTM) / PB
 * </pre>
 *
 * <p>为什么单独一个库、不塞进 f10.db：f10.db 已经有 1.3 GB（公告 + F10 缓存），
 * 再混进每天重算的估值快照，备份和迁移都会变得很难受；而这一整库都是**可以随时重算**的
 * 派生数据，删了重跑就行。</p>
 *
 * <p>沿用 holder 的两条约定：下载/派生数据一律 {@link VaultDb#openPlain} 明文；
 * SQLite 单连接，所有访问先进 {@link #LOCK}，否则后台同步写、界面读会撞 locked。</p>
 */
public final class RiskStore {

    private static final Object LOCK = new Object();
    private static Connection connection;
    private static String path;

    private static final String CREATE_EVENT =
            "CREATE TABLE IF NOT EXISTS T_RiskEvent("
                    + " CODE TEXT NOT NULL,"
                    + " NAME TEXT,"
                    + " EVENT_DATE INTEGER NOT NULL,"
                    + " CATEGORY TEXT NOT NULL,"
                    + " LEVEL INTEGER NOT NULL,"
                    + " TITLE TEXT,"
                    + " SOURCE TEXT NOT NULL,"
                    + " REF TEXT NOT NULL,"
                    + " PRIMARY KEY(CODE, EVENT_DATE, CATEGORY, REF))";

    private static final String[] EVENT_INDEXES = {
            "CREATE INDEX IF NOT EXISTS IDX_RISK_DATE ON T_RiskEvent(EVENT_DATE)",
            "CREATE INDEX IF NOT EXISTS IDX_RISK_CODE ON T_RiskEvent(CODE, EVENT_DATE)",
    };

    private static final String CREATE_LIFT =
            "CREATE TABLE IF NOT EXISTS T_Lift("
                    + " CODE TEXT NOT NULL,"
                    + " NAME TEXT,"
                    + " FREE_DATE INTEGER NOT NULL,"
                    + " SHARES REAL,"          // 万股
                    + " MARKET_CAP REAL,"      // 万元
                    + " FREE_RATIO REAL,"      // 占流通股比（小数，0.097 = 9.7%）
                    + " TOTAL_RATIO REAL,"     // 占总股本比（小数）
                    + " TYPE TEXT,"
                    + " PRIMARY KEY(CODE, FREE_DATE, TYPE))";

    private static final String CREATE_LIFT_INDEX =
            "CREATE INDEX IF NOT EXISTS IDX_LIFT_DATE ON T_Lift(FREE_DATE)";

    private static final String CREATE_VALUATION =
            "CREATE TABLE IF NOT EXISTS T_Valuation("
                    + " CODE TEXT NOT NULL,"
                    + " NAME TEXT,"
                    + " DATE INTEGER NOT NULL,"
                    + " CLOSE REAL,"
                    + " TOTAL_CAP REAL,"       // 元
                    + " FLOAT_CAP REAL,"       // 元
                    + " PE_TTM REAL,"
                    + " PB REAL,"
                    + " NET_PROFIT_TTM REAL,"  // 元
                    + " EQUITY REAL,"          // 归母净资产（元）
                    + " REPORT_DATE INTEGER,"  // 估值用的是哪一期财报（yyyyMMdd），界面要标出来
                    + " PE_STATIC REAL,"       // 总市值 ÷ 上年年报归母净利
                    + " PE_FORWARD REAL,"      // 总市值 ÷（本期累计净利 × 年化系数）
                    + " ROE_WEIGHT REAL,"      // 加权净资产收益率（%，累计，未年化）
                    + " PRIMARY KEY(CODE, DATE))";

    private static final String CREATE_META =
            "CREATE TABLE IF NOT EXISTS T_Meta( K TEXT PRIMARY KEY, V TEXT)";

    private RiskStore() {
    }

    public static String dbFilePath() {
        try {
            File finance = FinanceDBManager.resolveDbFile(null);
            File dir = finance.getParentFile();
            return new File(dir == null ? new File("data") : dir, "risk.db").getAbsolutePath();
        } catch (Exception e) {
            return "data/risk.db";
        }
    }

    /**
     * 老库补列。
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} 只对新库生效，已经建成旧结构的 T_Valuation
     * 不会因为建表语句改了而多出列 —— 必须自己 PRAGMA 查一遍再 ALTER，
     * 否则老用户升级后一读估值就报「no such column: PE_STATIC」。</p>
     */
    private static void migrateValuationColumns(Connection conn) throws SQLException {
        java.util.Set<String> has = new java.util.HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(T_Valuation)")) {
            while (rs.next()) {
                has.add(rs.getString("name").toUpperCase(java.util.Locale.ROOT));
            }
        }
        String[][] wanted = {
                {"REPORT_DATE", "INTEGER"},
                {"PE_STATIC", "REAL"},
                {"PE_FORWARD", "REAL"},
                {"ROE_WEIGHT", "REAL"},
        };
        try (Statement stmt = conn.createStatement()) {
            for (String[] column : wanted) {
                if (!has.contains(column[0])) {
                    stmt.execute("ALTER TABLE T_Valuation ADD COLUMN " + column[0] + " " + column[1]);
                }
            }
        }
    }

    static Connection open() throws SQLException {
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
            stmt.execute(CREATE_EVENT);
            for (String sql : EVENT_INDEXES) {
                stmt.execute(sql);
            }
            stmt.execute(CREATE_LIFT);
            stmt.execute(CREATE_LIFT_INDEX);
            stmt.execute(CREATE_VALUATION);
            stmt.execute(CREATE_META);
            migrateValuationColumns(conn);
        }
        connection = conn;
        path = target;
        return conn;
    }

    // ==================================================================
    //  写入
    // ==================================================================

    /** 某一类来源的风险事件整体替换（重跑不叠加） */
    public static void replaceEvents(String source, List<RiskEvent> events) {
        synchronized (LOCK) {
            try {
                Connection conn = open();
                conn.setAutoCommit(false);
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM T_RiskEvent WHERE SOURCE=?")) {
                    del.setString(1, source);
                    del.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO T_RiskEvent"
                                + "(CODE,NAME,EVENT_DATE,CATEGORY,LEVEL,TITLE,SOURCE,REF)"
                                + " VALUES(?,?,?,?,?,?,?,?)")) {
                    for (RiskEvent event : events) {
                        ps.setString(1, event.code());
                        ps.setString(2, event.name());
                        ps.setInt(3, event.date());
                        ps.setString(4, event.category().name());
                        ps.setInt(5, event.level());
                        ps.setString(6, event.title());
                        ps.setString(7, source);
                        ps.setString(8, event.ref());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("写入风险事件失败：" + e.getMessage(), e);
            }
        }
    }

    public static void replaceLifts(List<Lift> lifts) {
        synchronized (LOCK) {
            try {
                Connection conn = open();
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM T_Lift");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO T_Lift"
                                + "(CODE,NAME,FREE_DATE,SHARES,MARKET_CAP,FREE_RATIO,TOTAL_RATIO,TYPE)"
                                + " VALUES(?,?,?,?,?,?,?,?)")) {
                    for (Lift lift : lifts) {
                        ps.setString(1, lift.code());
                        ps.setString(2, lift.name());
                        ps.setInt(3, lift.freeDate());
                        ps.setDouble(4, lift.shares());
                        ps.setDouble(5, lift.marketCap());
                        ps.setDouble(6, lift.freeRatio());
                        ps.setDouble(7, lift.totalRatio());
                        ps.setString(8, lift.type());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("写入解禁数据失败：" + e.getMessage(), e);
            }
        }
    }

    public static void replaceValuations(List<Valuation> values) {
        synchronized (LOCK) {
            try {
                Connection conn = open();
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM T_Valuation");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO T_Valuation"
                                + "(CODE,NAME,DATE,CLOSE,TOTAL_CAP,FLOAT_CAP,PE_TTM,PB,"
                                + "NET_PROFIT_TTM,EQUITY,REPORT_DATE,PE_STATIC,PE_FORWARD,ROE_WEIGHT)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (Valuation v : values) {
                        ps.setString(1, v.code());
                        ps.setString(2, v.name());
                        ps.setInt(3, v.date());
                        ps.setDouble(4, v.close());
                        ps.setDouble(5, v.totalCap());
                        ps.setDouble(6, v.floatCap());
                        // 亏损股 PE、没净资产的 PB 是 NaN，落库要写 NULL：
                        // SQLite 把 NaN 存成 NULL，回头读出来却变成 0.0，
                        // 结果「亏损」看起来像「市盈率 0 倍」，排序还会排到最便宜那一头。
                        nullable(ps, 7, v.peTtm());
                        nullable(ps, 8, v.pb());
                        nullable(ps, 9, v.netProfitTtm());
                        nullable(ps, 10, v.equity());
                        ps.setInt(11, v.reportDate());
                        nullable(ps, 12, v.peStatic());
                        nullable(ps, 13, v.peForward());
                        nullable(ps, 14, v.roeWeight());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("写入估值数据失败：" + e.getMessage(), e);
            }
        }
    }

    // ==================================================================
    //  读取
    // ==================================================================

    public static List<RiskEvent> events(int fromDate, int toDate) {
        List<RiskEvent> list = new ArrayList<>();
        String sql = "SELECT CODE,NAME,EVENT_DATE,CATEGORY,LEVEL,TITLE,SOURCE,REF FROM T_RiskEvent"
                + " WHERE EVENT_DATE BETWEEN ? AND ? ORDER BY EVENT_DATE DESC, LEVEL DESC";
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(sql)) {
                ps.setInt(1, fromDate);
                ps.setInt(2, toDate);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readEvent(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取风险事件失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    public static List<RiskEvent> eventsOf(String code, int limit) {
        List<RiskEvent> list = new ArrayList<>();
        String sql = "SELECT CODE,NAME,EVENT_DATE,CATEGORY,LEVEL,TITLE,SOURCE,REF FROM T_RiskEvent"
                + " WHERE CODE=? ORDER BY EVENT_DATE DESC LIMIT ?";
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(sql)) {
                ps.setString(1, code);
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readEvent(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取个股风险失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    private static RiskEvent readEvent(ResultSet rs) throws SQLException {
        return new RiskEvent(rs.getString(1), rs.getString(2), rs.getInt(3),
                RiskCategory.of(rs.getString(4)), rs.getInt(5), rs.getString(6),
                rs.getString(7), rs.getString(8));
    }

    public static List<Lift> lifts(int fromDate, int toDate) {
        List<Lift> list = new ArrayList<>();
        String sql = "SELECT CODE,NAME,FREE_DATE,SHARES,MARKET_CAP,FREE_RATIO,TOTAL_RATIO,TYPE"
                + " FROM T_Lift WHERE FREE_DATE BETWEEN ? AND ? ORDER BY FREE_DATE";
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(sql)) {
                ps.setInt(1, fromDate);
                ps.setInt(2, toDate);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Lift(rs.getString(1), rs.getString(2), rs.getInt(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                                rs.getDouble(7), rs.getString(8)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取解禁数据失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    /** NaN 写 NULL（见 {@link #replaceValuations} 里的注释） */
    private static void nullable(PreparedStatement ps, int index, double value) throws SQLException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    /** NULL 读回 NaN，界面才能区分「没有」和「0 倍」 */
    private static double num(ResultSet rs, int index) throws SQLException {
        double value = rs.getDouble(index);
        return rs.wasNull() ? Double.NaN : value;
    }

    public static List<Valuation> valuations() {
        List<Valuation> list = new ArrayList<>();
        String sql = "SELECT CODE,NAME,DATE,CLOSE,TOTAL_CAP,FLOAT_CAP,PE_TTM,PB,NET_PROFIT_TTM,EQUITY,"
                + "REPORT_DATE,PE_STATIC,PE_FORWARD,ROE_WEIGHT"
                + " FROM T_Valuation ORDER BY TOTAL_CAP DESC";
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Valuation(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), num(rs, 7),
                            num(rs, 8), num(rs, 9), num(rs, 10), rs.getInt(11),
                            num(rs, 12), num(rs, 13), num(rs, 14)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取估值数据失败：" + e.getMessage(), e);
            }
        }
        return list;
    }

    public static int maxValuationDate() {
        synchronized (LOCK) {
            try (Statement stmt = open().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(DATE) FROM T_Valuation")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    public static int count(String table) {
        synchronized (LOCK) {
            try (Statement stmt = open().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    public static int eventCount(int fromDate, int toDate) {        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(
                    "SELECT COUNT(*) FROM T_RiskEvent WHERE EVENT_DATE BETWEEN ? AND ?")) {
                ps.setInt(1, fromDate);
                ps.setInt(2, toDate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ==================================================================
    //  水位
    // ==================================================================

    public static void putMeta(String key, String value) {
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement(
                    "INSERT OR REPLACE INTO T_Meta(K,V) VALUES(?,?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入水位失败：" + e.getMessage(), e);
            }
        }
    }

    public static String meta(String key) {
        synchronized (LOCK) {
            try (PreparedStatement ps = open().prepareStatement("SELECT V FROM T_Meta WHERE K=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                return null;
            }
        }
    }

    // ==================================================================
    //  行对象
    // ==================================================================

    /** 一条解禁记录（单位：万股 / 万元，比例是小数） */
    public record Lift(String code, String name, int freeDate, double shares, double marketCap,
                       double freeRatio, double totalRatio, String type) {
    }

    /**
     * 一条估值快照（金额单位：元）。
     *
     * <p>四种 PE 口径并存是故意的 —— 它们回答的是不同问题：</p>
     * <ul>
     *   <li>{@code peStatic} 静态：总市值 ÷ <b>上年年报</b>归母净利，最保守，不猜今年；</li>
     *   <li>{@code peForward} 动态：总市值 ÷ <b>本期累计净利年化</b>（一季报×4、半年报×2、三季报×4/3），
     *       假设今年剩下的日子按目前节奏走；</li>
     *   <li>{@code peTtm} 滚动：总市值 ÷ <b>最近四个季度</b>归母净利，不看日历年，最贴近「当下赚钱能力」；</li>
     *   <li>{@code roeWeight} 加权净资产收益率：累计口径、<b>不年化</b>（三季报就是前九个月），
     *       所以它只能同报告期横向比，别拿 Q3 的 8% 去和年报的 12% 比。</li>
     * </ul>
     *
     * <p>{@code reportDate} 是这组数字用的财报期，界面必须标出来 ——
     * 不标的话，同一个 PE 在 4 月和 5 月是两个完全不同的东西。</p>
     */
    public record Valuation(String code, String name, int date, double close, double totalCap,
                            double floatCap, double peTtm, double pb, double netProfitTtm,
                            double equity, int reportDate, double peStatic, double peForward,
                            double roeWeight) {
    }
}
