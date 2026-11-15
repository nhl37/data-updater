package updater.f10;

import updater.db.VaultDb;
import updater.db.FinanceDBManager;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F10 数据的本地缓存库（默认 {@code data/f10.db}，与财报库同目录）。
 *
 * <p>两类表：</p>
 * <pre>
 *   T_Announcement  公告落表：K线图要在几秒钟内按日期取到「哪天有重大公告」，不能每次都联网
 *   T_F10Cache      股东 / 板块题材的原始 JSON 缓存：它们按季度或按周才变，没必要每次开窗口都拉
 * </pre>
 *
 * <p>为什么不放 {@code data/finance.db}：财报库是「导入 gpcw 文件」的产物，会被重新导入覆盖，
 * F10 的获取缓存混进去容易被一起清掉。</p>
 */
public final class F10Store {

    /** 股东数据（十大流通股东）按报告期更新，缓存一天足够 */
    public static final long TTL_HOLDER = 24 * 3600 * 1000L;

    /** 板块/题材一周变不了几次 */
    public static final long TTL_CONCEPT = 3 * 24 * 3600 * 1000L;

    /** 公告一天看一次就够；每天盘后新公告多，所以按「当天内」复用 */
    public static final long TTL_ANNOUNCEMENT = 6 * 3600 * 1000L;

    private static final String KIND_HOLDER = "holder";
    private static final String KIND_CONCEPT = "concept";
    private static final String KIND_ANNOUNCE_AT = "ann_at";
    /** 全市场公告河流的同步水位（不是某只股票，用固定假代码占位） */
    private static final String KIND_ANNOUNCE_FEED = "ann_feed";
    private static final String FEED_CODE = "__market__";

    private static final String CREATE_ANNOUNCEMENT =
            "CREATE TABLE IF NOT EXISTS T_Announcement("
                    + " CODE TEXT NOT NULL,"
                    + " ART_CODE TEXT NOT NULL,"
                    + " NOTICE_DATE INTEGER NOT NULL,"
                    + " TITLE TEXT,"
                    + " COLUMN_NAME TEXT,"
                    + " SENTIMENT INTEGER NOT NULL,"
                    + " WEIGHT INTEGER NOT NULL,"
                    + " KEYWORD TEXT,"
                    + " PRIMARY KEY(CODE, ART_CODE))";

    private static final String CREATE_ANNOUNCEMENT_INDEX =
            "CREATE INDEX IF NOT EXISTS IDX_ANN_CODE_DATE ON T_Announcement(CODE, NOTICE_DATE)";

    private static final String CREATE_CACHE =
            "CREATE TABLE IF NOT EXISTS T_F10Cache("
                    + " CODE TEXT NOT NULL,"
                    + " KIND TEXT NOT NULL,"
                    + " FETCHED_AT INTEGER NOT NULL,"
                    + " PAYLOAD TEXT,"
                    + " PRIMARY KEY(CODE, KIND))";

    private static Connection connection;
    private static String path;

    private F10Store() {
    }

    public static String dbFilePath() {
        try {
            File finance = FinanceDBManager.resolveDbFile(null);
            File dir = finance.getParentFile();
            return new File(dir == null ? new File("data") : dir, "f10.db").getAbsolutePath();
        } catch (Exception e) {
            return "data/f10.db";
        }
    }

    private static synchronized Connection open() throws SQLException {
        String target = dbFilePath();
        if (connection != null && !connection.isClosed() && target.equals(path)) {
            return connection;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 换库时旧连接关不掉也不影响新连接
            }
        }
        File file = new File(target);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        // F10 是抓来的数据（股东 / 板块 / 公告），按「下载的数据一律明文」处理。
        // 代价是这份缓存能被拷贝分享，收益是换机器不用重抓十几分钟、也不会因为保险箱打不开而死库；
        // 情绪/权重这些派生列本来就能按公告重算，不值得为它把整个库锁起来。
        connection = VaultDb.openPlain(target);
        path = target;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_ANNOUNCEMENT);
            stmt.execute(CREATE_ANNOUNCEMENT_INDEX);
            stmt.execute(CREATE_CACHE);
        }
        return connection;
    }

    // ===================== 公告 =====================

    /**
     * 覆盖写某只股票的公告：先删后插，避免退市/更名后残留看不到的旧记录。
     * 公告按 art_code 唯一，重复拉取不会重复。
     */
    public static synchronized void saveAnnouncements(String code, List<Announcement> items)
            throws SQLException {
        String key = F10Api.plain(code);
        if (key.isEmpty()) {
            return;
        }
        Connection conn = open();
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM T_Announcement WHERE CODE = ?")) {
            delete.setString(1, key);
            delete.executeUpdate();
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT OR REPLACE INTO T_Announcement"
                            + "(CODE, ART_CODE, NOTICE_DATE, TITLE, COLUMN_NAME, SENTIMENT, WEIGHT, KEYWORD)"
                            + " VALUES(?,?,?,?,?,?,?,?)")) {
                for (Announcement item : items) {
                    insert.setString(1, key);
                    insert.setString(2, item.artCode());
                    insert.setInt(3, item.date());
                    insert.setString(4, item.title());
                    insert.setString(5, item.columnName());
                    insert.setInt(6, item.sentiment().code);
                    insert.setInt(7, item.weight());
                    insert.setString(8, item.keyword());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /**
     * 用当前判定规则把整张表重打一遍标签。
     *
     * <p>表里的 SENTIMENT/WEIGHT 是「写入那一刻」的规则算出来的，规则一改就和新逻辑对不上；
     * 界面读的时候会在内存里重判（见 {@code AnnouncementRepository#cached}），但直接用 SQL
     * 分析这张表就会看到两套口径。所以每次同步公告顺手整表重算一遍——两万条约 1 秒。</p>
     *
     * @return 实际有变化的行数
     */
    public static synchronized int rejudgeAll() {
        List<String[]> rows = new ArrayList<>();
        List<int[]> old = new ArrayList<>();
        try (PreparedStatement ps = open().prepareStatement(
                "SELECT CODE, ART_CODE, TITLE, COLUMN_NAME, SENTIMENT, WEIGHT FROM T_Announcement");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                old.add(new int[]{rs.getInt(5), rs.getInt(6)});
            }
        } catch (SQLException e) {
            return 0;
        }
        Connection conn;
        try {
            conn = open();
        } catch (SQLException e) {
            return 0;
        }
        int changed = 0;
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE T_Announcement SET SENTIMENT=?, WEIGHT=?, KEYWORD=?"
                        + " WHERE CODE=? AND ART_CODE=?")) {
            for (int i = 0; i < rows.size(); i++) {
                String[] row = rows.get(i);
                AnnouncementJudge.Judgment judgment = AnnouncementJudge.judge(row[2], row[3]);
                if (old.get(i)[0] == judgment.sentiment().code
                        && old.get(i)[1] == judgment.weight()) {
                    continue;
                }
                update.setInt(1, judgment.sentiment().code);
                update.setInt(2, judgment.weight());
                update.setString(3, judgment.keyword());
                update.setString(4, row[0]);
                update.setString(5, row[1]);
                update.addBatch();
                changed++;
            }
            update.executeBatch();
        } catch (SQLException e) {
            return changed;
        }
        return changed;
    }

    /**
     * 批量写入全市场公告（来自公告「河流」接口，一次覆盖几千只股票）。
     *
     * <p>和 {@link #saveAnnouncements} 的区别：**不删旧记录**。河流只给最近几天，
     * 删了就把某只股票更早的历史公告一起清掉，F10 列表立刻变空。
     * 同一条公告重复拉到由主键 (CODE, ART_CODE) 覆盖，不会重复。</p>
     *
     * @return 实际写入条数
     */
    public static synchronized int saveAnnouncementBatch(
            java.util.Map<String, List<Announcement>> byCode) throws SQLException {
        if (byCode == null || byCode.isEmpty()) {
            return 0;
        }
        Connection conn = open();
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        int written = 0;
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT OR REPLACE INTO T_Announcement"
                        + "(CODE, ART_CODE, NOTICE_DATE, TITLE, COLUMN_NAME, SENTIMENT, WEIGHT, KEYWORD)"
                        + " VALUES(?,?,?,?,?,?,?,?)")) {
            for (java.util.Map.Entry<String, List<Announcement>> entry : byCode.entrySet()) {
                String key = F10Api.plain(entry.getKey());
                if (key.isEmpty()) {
                    continue;
                }
                for (Announcement item : entry.getValue()) {
                    insert.setString(1, key);
                    insert.setString(2, item.artCode());
                    insert.setInt(3, item.date());
                    insert.setString(4, item.title());
                    insert.setString(5, item.columnName());
                    insert.setInt(6, item.sentiment().code);
                    insert.setInt(7, item.weight());
                    insert.setString(8, item.keyword());
                    insert.addBatch();
                    written++;
                }
            }
            insert.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
        return written;
    }

    /**
     * 读本地缓存的公告（按日期倒序）。缓存过期时也照读——联网失败时至少还有上次的内容。
     */
    public static synchronized List<Announcement> loadAnnouncements(String code, int limit)
            throws SQLException {
        String key = F10Api.plain(code);
        if (key.isEmpty()) {
            return new ArrayList<>();
        }
        List<Announcement> result = new ArrayList<>();
        Connection conn = open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ART_CODE, NOTICE_DATE, TITLE, COLUMN_NAME, SENTIMENT, WEIGHT, KEYWORD"
                        + " FROM T_Announcement WHERE CODE = ? ORDER BY NOTICE_DATE DESC LIMIT ?")) {
            ps.setString(1, key);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Announcement(
                            rs.getString(1),
                            rs.getInt(2),
                            rs.getString(3),
                            rs.getString(4),
                            Announcement.Sentiment.of(rs.getInt(5)),
                            rs.getInt(6),
                            rs.getString(7)));
                }
            }
        }
        return result;
    }

    // ===================== 通用 JSON 缓存 =====================

    /** 取缓存；不存在或超过 ttl 时返回 null */
    public static synchronized String getCache(String code, String kind, long ttl) {
        String key = F10Api.plain(code);
        if (key.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = open().prepareStatement(
                "SELECT PAYLOAD, FETCHED_AT FROM T_F10Cache WHERE CODE = ? AND KIND = ?")) {
            ps.setString(1, key);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long fetchedAt = rs.getLong(2);
                if (ttl > 0 && System.currentTimeMillis() - fetchedAt > ttl) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            // 缓存读不到就走网络，不必把异常抛给界面
            return null;
        }
    }

    public static synchronized void putCache(String code, String kind, String payload) {
        String key = F10Api.plain(code);
        if (key.isEmpty() || payload == null) {
            return;
        }
        try (PreparedStatement ps = open().prepareStatement(
                "INSERT OR REPLACE INTO T_F10Cache(CODE, KIND, FETCHED_AT, PAYLOAD) VALUES(?,?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, kind);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, payload);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 缓存写失败不影响本次显示
        }
    }

    /** 公告上次成功拉取的时间（毫秒），没有记录时返回 0 */
    public static synchronized long announcementFetchedAt(String code) {
        String text = getCache(code, KIND_ANNOUNCE_AT, 0);
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static void markAnnouncementsFetched(String code) {
        putCache(code, KIND_ANNOUNCE_AT, String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 股东缓存的 KIND：<b>按报告期分桶</b>，不同季度的股东数据互不覆盖。
     *
     * <p>为什么要分桶：财报可以按季度分别下载，「下载哪个季度的财报就更新哪个季度的股东」，
     * 那么 20260630 与 20260930 两份股东数据必须能同时留在缓存里，
     * 否则按季度刷新时后刷的会把先刷的顶掉，来回切换就永远命中不了缓存。</p>
     *
     * @param reportDate 报告期 yyyyMMdd；0 或负数表示「不限期别」（取接口最新一期，理财画面走这条）
     */
    public static String holderKind(int reportDate) {
        return reportDate > 0 ? KIND_HOLDER + ":" + reportDate : KIND_HOLDER;
    }

    /** 股东缓存：按「不限期别」那个桶读，等价于 {@code holderCache(code, holderKind(0), ttl)} */
    public static String holderCache(String code, long ttl) {
        return holderCache(code, holderKind(0), ttl);
    }

    /** 股东缓存：按指定 KIND 读（KIND 由 {@link #holderKind(int)} 生成） */
    public static String holderCache(String code, String kind, long ttl) {
        return getCache(code, kind, ttl);
    }

    public static void putHolderCache(String code, String payload) {
        putHolderCache(code, holderKind(0), payload);
    }

    public static void putHolderCache(String code, String kind, String payload) {
        putCache(code, kind, payload);
    }

    /**
     * 某个 KIND 桶里已经有多少只股票有缓存（不看是否过期）。
     *
     * <p>用途：刷新前先告诉用户「这一期已经有 N 只的数据了，要不要覆盖」，
     * 而不是闷头跑十几分钟把人家手工补过的数据全顶掉。</p>
     */
    public static synchronized int countCache(String kind) {
        try (PreparedStatement ps = open().prepareStatement(
                "SELECT COUNT(*) FROM T_F10Cache WHERE KIND = ?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 某类缓存的全部桶里一共有多少条（{@code holder} + {@code holder:20260930} …） */
    public static synchronized int countCachePrefix(String prefix) {
        try (PreparedStatement ps = open().prepareStatement(
                "SELECT COUNT(*) FROM T_F10Cache WHERE KIND = ? OR KIND LIKE ?")) {
            ps.setString(1, prefix);
            ps.setString(2, prefix + ":%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 某个 KIND 桶里已经有数据的股票代码（6 位，不看是否过期）。
     *
     * <p>用途：查缺补漏时算「这一季还差哪几只」——拿它跟「应有清单」求差集，
     * 补的时候只补差出来的那几只，不必再整季五千只重刷一遍。</p>
     */
    public static synchronized Set<String> cachedCodes(String kind) {
        return cachedCodesSince(kind, null);
    }

    /**
     * 一批桶里已有数据的股票代码并集（6 位）。
     *
     * @param prefix  桶名前缀，如 {@code holder}
     * @param fromKind 桶名起点（含），如 {@code holder:20250930}；null 表示不限
     *
     * <p>「应有清单」用它、而不是本地日线库那份代码表：东财本来就没有部分股票的十大流通股东
     * （次新、B 股、无流通股东的特殊标的），拿日线库求差会差出几百只永远补不上的「假缺失」，
     * 每次点修复都白跑一遍。历史上真正抓到过数据的代码才是可信的应有集合。</p>
     */
    public static synchronized Set<String> cachedCodesSince(String prefix, String fromKind) {
        Set<String> out = new LinkedHashSet<String>();
        String sql = fromKind == null || fromKind.trim().isEmpty()
                ? "SELECT DISTINCT CODE FROM T_F10Cache WHERE KIND = ? OR KIND LIKE ?"
                : "SELECT DISTINCT CODE FROM T_F10Cache WHERE KIND >= ? AND KIND LIKE ?";
        try (PreparedStatement ps = open().prepareStatement(sql)) {
            ps.setString(1, fromKind == null || fromKind.trim().isEmpty() ? prefix : fromKind.trim());
            ps.setString(2, prefix + ":%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    if (code != null && !code.trim().isEmpty()) {
                        out.add(code.trim());
                    }
                }
            }
        } catch (SQLException e) {
            return out;
        }
        return out;
    }

    public static String conceptCache(String code, long ttl) {
        return getCache(code, KIND_CONCEPT, ttl);
    }

    public static void putConceptCache(String code, String payload) {
        putCache(code, KIND_CONCEPT, payload);
    }

    /**
     * 把所有股票的某类缓存放废，逼下一次访问重新联网。
     *
     * <p>用途：财报导入后股东数据整批换代，但 TTL 还没到，不清掉的话批量刷新会全部命中旧缓存、
     * 一只都抓不到新的。清完再刷，抓成功的会重新写回缓存，等于天然的断点续跑。</p>
     */
    public static synchronized int invalidateKind(String kind) {
        try (PreparedStatement ps = open().prepareStatement(
                "DELETE FROM T_F10Cache WHERE KIND = ?")) {
            ps.setString(1, kind);
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 清掉<b>所有</b>股东缓存：{@code holder} 这个通用桶，以及 {@code holder:20260930}
     * 这类按报告期分开的桶。
     *
     * <p>必须按前缀删：股东缓存已经按报告期分桶（见 {@link #holderKind(int)}），
     * 只删 {@code KIND = 'holder'} 的话，按季度写入的那些一条都清不掉，
     * 批量刷新会全部命中旧缓存、一只都抓不到新的。</p>
     */
    public static int invalidateHolders() {
        return invalidateKindPrefix(KIND_HOLDER);
    }

    public static int invalidateConcepts() {
        return invalidateKind(KIND_CONCEPT);
    }

    /**
     * 删除某类缓存的全部桶：{@code KIND = prefix} 与 {@code KIND LIKE 'prefix:%'}
     *
     * @param prefix 不含冒号的前缀，如 {@code holder}
     * @return 实际删除的行数
     */
    public static synchronized int invalidateKindPrefix(String prefix) {
        try (PreparedStatement ps = open().prepareStatement(
                "DELETE FROM T_F10Cache WHERE KIND = ? OR KIND LIKE ?")) {
            ps.setString(1, prefix);
            ps.setString(2, prefix + ":%");
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 公告河流上次同步到的时间（毫秒），没有记录返回 0 */
    public static synchronized long announcementFeedAt() {
        try (PreparedStatement ps = open().prepareStatement(
                "SELECT FETCHED_AT FROM T_F10Cache WHERE CODE = ? AND KIND = ?")) {
            ps.setString(1, FEED_CODE);
            ps.setString(2, KIND_ANNOUNCE_FEED);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    public static synchronized void markAnnouncementFeed() {
        try (PreparedStatement ps = open().prepareStatement(
                "INSERT OR REPLACE INTO T_F10Cache(CODE, KIND, FETCHED_AT, PAYLOAD) VALUES(?,?,?,?)")) {
            ps.setString(1, FEED_CODE);
            ps.setString(2, KIND_ANNOUNCE_FEED);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, "");
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 水位写不进去最多下次多翻几页
        }
    }
}
