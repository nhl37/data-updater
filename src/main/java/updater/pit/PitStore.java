package updater.pit;

import updater.db.FinanceDBManager;
import updater.db.VaultDb;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报「实际披露日」库（默认 {@code data/pit.db}，与财报库同目录、明文）。
 *
 * <pre>
 *   T_FinNotice   CODE + REPORT_DATE + NOTICE_DATE
 * </pre>
 *
 * <p><b>这张表解决什么问题</b>：财报库里一行数据的键是「报告期」（3/31、6/30、9/30、12/31），
 * 但真正能被人看到的时间是「披露日」——年报最晚拖到次年 4 月底，一季报 4 月底，
 * 半年报 8 月底，三季报 10 月底。回测如果按报告期取数，9 月 30 日的三季报会被当成
 * 9 月 30 日就能用，而它其实是 10 月 24 日才公布的——<b>凭空多出三周多的信息优势，
 * 这段区间的收益全是假的</b>。这就是典型的前视偏差（look-ahead bias）。</p>
 *
 * <p>所以回测取财报要问的是「这一天，<b>哪一期已经披露了</b>」，
 * 而不是「这一天，哪一期的报告期已经过去了」。这张表就是回答前者的。</p>
 *
 * <p><b>只有这一张表、没有别的用法</b>：{@link updater.finance.FinanceSnapshot} 是
 * 全市场财报取数的唯一入口，它在里面把「报告期 ≤ 今天」换成「披露日 ≤ 今天」，
 * 脚本、选股、回测、估值全部自动受益，不需要各处去改。</p>
 *
 * <p>数据来自东财业绩报表（{@code RPT_LICO_FN_CPD}）的 {@code NOTICE_DATE}，
 * 拉取见 {@link PitSync}。没有这条记录时退回旧口径（按报告期），
 * 也就是「不知道就照老规矩办」，不会因为缺数据把回测结果算崩。</p>
 */
public final class PitStore {

    private static final Object LOCK = new Object();
    /** 一只股票的披露日时间线：一维数组 {@code [报告期, 披露日, 报告期, 披露日, ...]}，按报告期倒序 */
    private static final Map<String, int[]> CACHE = new HashMap<>();
    private static Connection connection;
    private static String path;
    private static long cacheStamp = -1;
    private static long stampCheckedAt;
    private static boolean loaded;
    private static final long STAMP_INTERVAL = 5000L;
    private static boolean enabled = true;

    private static final String CREATE =
            "CREATE TABLE IF NOT EXISTS T_FinNotice("
                    + " CODE TEXT NOT NULL,"
                    + " REPORT_DATE INTEGER NOT NULL,"
                    + " NOTICE_DATE INTEGER NOT NULL,"
                    + " PRIMARY KEY(CODE, REPORT_DATE))";

    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS IDX_NOTICE_REPORT ON T_FinNotice(REPORT_DATE)";

    /** 一条披露记录 */
    public record Notice(String code, int reportDate, int noticeDate) {
    }

    private PitStore() {
    }

    public static String dbFilePath() {
        try {
            File finance = FinanceDBManager.resolveDbFile(null);
            File dir = finance.getParentFile();
            return new File(dir == null ? new File("data") : dir, "pit.db").getAbsolutePath();
        } catch (Exception e) {
            return "data/pit.db";
        }
    }

    /** 读路径不改库：文件不存在就当没有数据，不去创建一个空库 */
    private static boolean exists() {
        try {
            return new File(dbFilePath()).isFile();
        } catch (Exception e) {
            return false;
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
            stmt.execute(CREATE);
            stmt.execute(CREATE_INDEX);
        }
        connection = conn;
        path = target;
        return conn;
    }

    /**
     * 整体替换 {@code fromReportDate} 及之后的披露日。
     *
     * <p>只删这一段而不是清空：同步窗口是「2024 年起」，早于窗口的历史数据留着，
     * 否则每同步一次就把老区间抹掉，回测一跑就退回旧口径。</p>
     */
    public static int replaceAll(int fromReportDate, List<Notice> list) {
        synchronized (LOCK) {
            try {
                Connection conn = open();
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM T_FinNotice WHERE REPORT_DATE >= ?")) {
                    del.setInt(1, fromReportDate);
                    del.executeUpdate();
                }
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO T_FinNotice(CODE, REPORT_DATE, NOTICE_DATE)"
                                + " VALUES(?,?,?)")) {
                    int pending = 0;
                    for (Notice notice : list) {
                        ps.setString(1, notice.code());
                        ps.setInt(2, notice.reportDate());
                        ps.setInt(3, notice.noticeDate());
                        ps.addBatch();
                        if (++pending % 2000 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(true);
                invalidateLocked();
                return list.size();
            } catch (SQLException e) {
                throw new IllegalStateException("写入披露日期失败：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 某个报告期的实际披露日；没有记录返回 0。
     *
     * <p>热路径：脚本扫全市场时一只票会问几百次，所以数据全在内存里查，
     * 只有第一次（或库文件变了之后）才读盘。</p>
     */
    public static int noticeDate(String code, int reportDate) {
        if (!enabled || reportDate <= 0) {
            return 0;
        }
        String key = six(code);
        if (key.isEmpty()) {
            return 0;
        }
        int[] line;
        synchronized (LOCK) {
            line = timeline().get(key);
        }
        if (line == null) {
            return 0;
        }
        for (int i = 0; i + 1 < line.length; i += 2) {
            if (line[i] == reportDate) {
                return line[i + 1];
            }
        }
        return 0;
    }

    /** 「这一天能看到的最近一期报告期」，调试与界面提示用；没有返回 0 */
    public static int visibleReportDate(String code, int date) {
        String key = six(code);
        int[] line;
        synchronized (LOCK) {
            line = timeline().get(key);
        }
        if (line == null) {
            return 0;
        }
        for (int i = 0; i + 1 < line.length; i += 2) {
            int notice = line[i + 1] > 0 ? line[i + 1] : line[i];
            if (notice <= date) {
                return line[i];
            }
        }
        return 0;
    }

    public static int count() {
        return scalar("SELECT COUNT(*) FROM T_FinNotice");
    }

    public static int minReportDate() {
        return scalar("SELECT MIN(REPORT_DATE) FROM T_FinNotice");
    }

    public static int maxReportDate() {
        return scalar("SELECT MAX(REPORT_DATE) FROM T_FinNotice");
    }

    private static int scalar(String sql) {
        if (!exists()) {
            return 0;
        }
        synchronized (LOCK) {
            try (Statement stmt = open().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 给 A/B 对比用：关掉之后 {@link #noticeDate} 一律返回 0，财报取值退回「按报告期」 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void invalidate() {
        synchronized (LOCK) {
            invalidateLocked();
        }
    }

    private static void invalidateLocked() {
        CACHE.clear();
        loaded = false;
        cacheStamp = -1;
        stampCheckedAt = 0;
    }

    /** 调用方必须持有 {@link #LOCK} */
    private static Map<String, int[]> timeline() {
        long now = System.currentTimeMillis();
        if (now - stampCheckedAt >= STAMP_INTERVAL) {
            stampCheckedAt = now;
            long stamp = exists() ? new File(dbFilePath()).lastModified() : 0L;
            if (stamp != cacheStamp || !loaded) {
                CACHE.clear();
                loaded = false;
                cacheStamp = stamp;
            }
        }
        if (loaded || !exists()) {
            loaded = true;
            return CACHE;
        }
        load();
        loaded = true;
        return CACHE;
    }

    private static void load() {
        String sql = "SELECT CODE, REPORT_DATE, NOTICE_DATE FROM T_FinNotice"
                + " ORDER BY CODE, REPORT_DATE DESC";
        Map<String, java.util.ArrayList<Integer>> temp = new HashMap<>();
        try (Statement stmt = open().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String code = six(rs.getString(1));
                if (code.isEmpty()) {
                    continue;
                }
                temp.computeIfAbsent(code, k -> new java.util.ArrayList<>(24))
                        .add(rs.getInt(2));
                temp.get(code).add(rs.getInt(3));
            }
        } catch (SQLException e) {
            return;
        }
        for (Map.Entry<String, java.util.ArrayList<Integer>> entry : temp.entrySet()) {
            java.util.ArrayList<Integer> values = entry.getValue();
            int[] line = new int[values.size()];
            for (int i = 0; i < line.length; i++) {
                line[i] = values.get(i);
            }
            CACHE.put(entry.getKey(), line);
        }
    }

    /** 只留六位数字代码：{@code sh600000} / {@code 600000.SH} → {@code 600000} */
    public static String six(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        String digits = sb.toString();
        if (digits.length() > 6) {
            return digits.substring(digits.length() - 6);
        }
        return digits.length() == 6 ? digits : "";
    }
}
