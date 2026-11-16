package updater.fetchdata.tdx.min;

import updater.db.DBConnection;
import updater.fetchdata.tdx.TdxDataDir;
import updater.log.Log;
import updater.util.FileTool;

import javax.swing.JOptionPane;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从本地数据来源的文件里把 5 分钟线导入数据库。
 *
 * <p>数据落在**每只股票自己的库里**（{@code data/db/sh600000.db}，和 {@code T_D_Day} 同一个文件）：
 * 除权因子 {@code T_Divdend} 也在这个库里，将来要对齐复权口径也是一句话的事。</p>
 *
 * <p><b>库里只存原始 5 分钟</b>（{@code T_D_Min5}），30 分钟不落表 —— 更新器只负责把数据抓下来
 * 写进库，不做任何周期派生。以前生成过的 {@code T_D_Min30} 会在这里被顺手删掉。</p>
 *
 * <pre>
 *   T_D_Min5   TIME(BIGINT,yyyyMMddHHmm) OPEN HIGH LOW CLOSE VOL AMO
 * </pre>
 *
 * <p><b>只增不删</b>：本地数据来源的分钟线文件本身是滚动的（大约 6 个多月），但本地库不跟着裁 ——
 * 以前按 6 个月窗口删，用户攒下来的老数据会被悄悄清掉，现在写进去就一直留着，
 * 要清也是用户自己决定。</p>
 *
 * <p>增量：库里已有最后时刻之后的才插入。首次导入是全量（5321 只 × 约 6600 根，要跑几分钟），
 * 之后每天盘后只补当天的 48 根，几秒钟完事。</p>
 */
public class MinDataUpdater {

    /**
     * 本地数据来源分钟文件大约只滚动 6 个多月。
     *
     * <p><b>只用来判「这只还算活跃吗」</b>：查缺补漏碰上停牌 / 退市的股票，末根早就跑出这个窗口，
     * 再判它「缺」会刷出一堆永远修不好的红条。写库、补数、清理都不看它 ——
     * 用户库里的 5 分钟线一律不删，也不按月份裁掉。</p>
     */
    public static final int STALE_MONTHS = 6;

    private static final String CREATE_MIN5 =
            "CREATE TABLE IF NOT EXISTS T_D_Min5("
                    + "TIME BIGINT PRIMARY KEY NOT NULL,"
                    + " OPEN INT NOT NULL,"
                    + " CLOSE INT NOT NULL,"
                    + " HIGH INT NOT NULL,"
                    + " LOW INT NOT NULL,"
                    + "VOL BIGINT NOT NULL,"
                    + "AMO BIGINT NOT NULL)";

    /** 以前把 30 分钟落过表，现在改成查询时现算：老库里的这张表顺手删掉，省空间也省得被人读旧值 */
    private static final String DROP_MIN30 = "DROP TABLE IF EXISTS T_D_Min30";

    private static final String INSERT_MIN5 =
            "INSERT OR REPLACE INTO T_D_Min5 (TIME,OPEN,CLOSE,HIGH,LOW,VOL,AMO) VALUES (?,?,?,?,?,?,?)";

    public static void main(String[] args) {
        Log.info("MinDataUpdater-start");
        try {
            int[] stat = update();
            Log.info("MinDataUpdater-end  股票 " + stat[0] + " 只，写入 5 分钟 " + stat[1]
                    + " 根，跳过 " + stat[2] + " 只");
        } catch (Exception e) {
            Log.error("分钟数据更新失败：" + e.getMessage());
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "分钟数据更新失败：\n" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @return {股票数, 写入的5分钟根数, 跳过的股票数}
     */
    public static int[] update() throws Exception {
        return update(null, null);
    }

    /**
     * 带进度与停止标志的版本（盘后下载顺带补 5 分钟线走这条路）。
     *
     * @param log    进度文字，null 只写日志文件
     * @param cancel 停止标志，可为 null
     * @return {股票数, 写入的5分钟根数, 跳过的股票数}
     */
    public static int[] update(java.util.function.Consumer<String> log,
                               java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        return update(log, cancel, null);
    }

    /**
     * @param onlyCodes 只补这几只（盘后下载的「重试失败 / 接着跑」），null = 全市场
     */
    public static int[] update(java.util.function.Consumer<String> log,
                               java.util.concurrent.atomic.AtomicBoolean cancel,
                               java.util.Collection<String> onlyCodes) throws Exception {
        return update(log, cancel, onlyCodes, false);
    }

    /**
     * 「补全到本地最新」：{@code fillGaps=true} 时连中间断掉的几天一起补。
     *
     * <p>日常盘后只补末根之后的增量（{@code fillGaps=false}，最快）；用户在
     * 「分钟数据」画面上点「补全到本地最新」时才走这条，把库里缺的那几段补齐 ——
     * 判重方式从「比末根时间」改成「按已有时刻逐根比」，一只多读约 6600 行。</p>
     *
     * @param fillGaps true = 连中间断档一起补
     */
    public static int[] update(java.util.function.Consumer<String> log,
                               java.util.concurrent.atomic.AtomicBoolean cancel,
                               java.util.Collection<String> onlyCodes,
                               boolean fillGaps) throws Exception {
        File vipdoc = TdxDataDir.requireVipdoc();

        Map<String, File[]> files = collectFiles(vipdoc, true);
        if (onlyCodes != null && !onlyCodes.isEmpty()) {
            files.keySet().retainAll(new java.util.HashSet<String>(onlyCodes));
        }
        Log.info("分钟数据：本地数据来源目录 " + vipdoc + "，找到 " + files.size()
                + " 只股票的文件，只补不删" + (fillGaps ? "（连中间断档一起补）" : ""));
        report(log, "5 分钟线：本地数据来源里找到 " + files.size() + " 只股票的文件，只补不删"
                + "（库里已有的老数据一条都不动）" + (fillGaps ? "，连中间断掉的几段一起补" : ""));

        int done = 0;
        int min5Count = 0;
        int skipped = 0;
        // 读到的最后一根（全市场最大时刻）：只用来在日志里说一句「本地文件到哪一天了」，
        // 用户据此一眼判断行情客户端的分钟数据到底下没下下来，不参与任何写库判断
        long latest = 0L;
        long startTime = System.currentTimeMillis();

        for (Map.Entry<String, File[]> entry : files.entrySet()) {
            if (cancel != null && cancel.get()) {
                report(log, "5 分钟线：已停止，写入 " + min5Count + " 根后剩下的没跑");
                break;
            }
            String code = entry.getKey();
            File lc5 = entry.getValue()[0];
            File lc1 = entry.getValue()[1];
            try {
                List<TdxMinBar> bars = TdxMinDataReader.readFiveMinute(lc5, lc1);
                // read() 已按时间升序，最后一根最新；只有更早的数据就没什么可写的
                if (bars.isEmpty()) {
                    skipped++;
                    continue;
                }
                long last = bars.get(bars.size() - 1).getTime();
                if (last > latest) {
                    latest = last;
                }
                min5Count += writeBars(code, bars, fillGaps);
                done++;
            } catch (Exception e) {
                skipped++;
                Log.error("处理 " + code + " 的分钟数据失败：" + e.getMessage());
            }

            if (done % 200 == 0 && done > 0) {
                long now = System.currentTimeMillis();
                Log.info("分钟数据进度：已处理 " + done + "/" + files.size()
                        + "，写入 " + min5Count + " 根 5 分钟，耗时 "
                        + ((now - startTime) / 1000) + " 秒");
                report(log, "5 分钟线：已处理 " + done + "/" + files.size()
                        + "，写入 " + min5Count + " 根");
                startTime = now;
            }
        }
        if (latest > 0) {
            // 只报事实、不加判断：周末 / 节假日这天本来就不会有新数据，多说一句反而吓人
            report(log, "5 分钟线：本地分钟文件最新一根 " + latest
                    + "（这一趟写入 " + min5Count + " 根）");
            Log.info("分钟数据：本地文件最新一根 " + latest);
        }
        return new int[]{done, min5Count, skipped};
    }

    /** 进度文字出口：null 表示只写日志文件 */
    private static void report(java.util.function.Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    /**
     * 只更新一只股票（读文件 → 入库）。全量更新走 {@link #update()}，
     * 这个入口留给「某只股票数据不对，单独重刷一下」以及探针验证用。
     *
     * @param code sh600000 这种带市场前缀的代码
     * @return 写入的 5 分钟根数；找不到文件时返回 0
     */
    public static int updateOne(String code) throws Exception {
        File vipdoc = TdxDataDir.requireVipdoc();
        Map<String, File[]> files = collectFiles(vipdoc, true);
        File[] pair = files.get(code);
        if (pair == null) {
            return 0;
        }
        List<TdxMinBar> bars = TdxMinDataReader.readFiveMinute(pair[0], pair[1]);
        return writeBars(code, bars);
    }

    /**
     * 用<b>已经读出来的</b>分钟线写库（只补末尾增量，不删任何已有数据）。
     *
     * <p>查缺补漏用得上：它为了判「本地有没有落后」已经读过这只的文件了，
     * 再走 {@link #updateOne} 会把同一个文件读第二遍（全市场几千只，白读一分钟）。</p>
     *
     * @return 写入的 5 分钟根数（库里已有更晚的数据时为 0）
     */
    public static int writeBars(String code, List<TdxMinBar> bars) throws Exception {
        return writeBars(code, bars, false);
    }

    /**
     * @param fillGaps true = 连「中间断掉的某几天」一起补（查缺补漏的修复用），
     *                 false = 只补末根之后的增量（每天盘后走这条，最快）
     */
    public static int writeBars(String code, List<TdxMinBar> bars, boolean fillGaps)
            throws Exception {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        return writeOne(code, bars, fillGaps);
    }

    /**
     * 写一只股票：建表 → 插 5 分钟 → 删掉历史遗留的 30 分钟表。
     *
     * <p><b>不再删过期的 5 分钟线</b>：以前按 6 个月窗口删，用户攒下来的老数据会被悄悄清掉；
     * 现在只增不删，库涨多大由用户自己决定。</p>
     *
     * @param fillGaps true = 库里已有这个时刻也照样补（识别方式改成「已有时间来比」）；
     *                 false = 只插末根之后的（每天盘后走这条，一次 range 查询就够）
     * @return 写入的 5 分钟根数
     */
    private static int writeOne(String code, List<TdxMinBar> bars, boolean fillGaps)
            throws Exception {
        // 只写已经存在的日线库（collectFiles 已经过滤过，这里再兜一层，绝不凭空造库）
        Connection conn = DBConnection.connect(code);
        int min5 = 0;
        try {
            conn.setAutoCommit(false);
            ensureTables(conn);

            long lastTime = maxTime(conn, "T_D_Min5");
            // 中间断档（某天没跑盘后下载、或者补更早的缺口）只看末根是发现不了的：
            // 这时把保留期内已有的时刻读成集合，逐个对照。读数约 6600 行，只在修复时才走。
            java.util.Set<Long> existing = fillGaps
                    ? existingTimes(conn, bars.get(0).getTime()) : null;
            try (PreparedStatement ps = conn.prepareStatement(INSERT_MIN5)) {
                int pending = 0;
                for (TdxMinBar bar : bars) {
                    if (existing != null ? existing.contains(bar.getTime())
                            : bar.getTime() <= lastTime) {
                        continue;
                    }
                    ps.setLong(1, bar.getTime());
                    ps.setInt(2, bar.getOpen());
                    ps.setInt(3, bar.getClose());
                    ps.setInt(4, bar.getHigh());
                    ps.setInt(5, bar.getLow());
                    ps.setLong(6, bar.getVol());
                    ps.setLong(7, bar.getAmo());
                    ps.addBatch();
                    min5++;
                    if (++pending >= 800) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }

            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败就算了，反正这只股票会记进 skipped
            }
            throw e;
        } finally {
            // 全市场几千只，连接的页缓存不小；按对象关掉，别指望 close(kCode)
            DBConnection.closeQuietly(conn);
        }
        return min5;
    }

    /** 从 {@code from} 起库里已有的 5 分钟时刻（修复断档时用；下界取文件里最早那根，够快也不漏） */
    private static java.util.Set<Long> existingTimes(Connection conn, long from) throws SQLException {
        java.util.Set<Long> times = new java.util.HashSet<Long>(8192);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TIME FROM T_D_Min5 WHERE TIME >= ?")) {
            ps.setLong(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    times.add(rs.getLong(1));
                }
            }
        }
        return times;
    }

    /**
     * 建 5 分钟表，并删掉以前生成的 30 分钟表（30 分钟现在改成用的时候现算，不落表）。
     * 删表是幂等的，每次更新都执行一遍也只是个空操作。
     */
    private static void ensureTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_MIN5);
            stmt.execute(DROP_MIN30);
        }
    }

    private static long maxTime(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT IFNULL(MAX(TIME),0) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * 本地库里 5 分钟线的最后一根时间（yyyyMMddHHmm）。
     *
     * <p>查缺补漏靠它判「这只的分钟线有没有落后」：表不存在或表是空的都返 0，
     * 读失败也返 0 —— 分钟线只做粗判，不能因为读它失败就把整只股票判成坏库。</p>
     */
    public static long lastTime(Connection conn) {
        if (conn == null) {
            return 0;
        }
        try {
            if (!hasTable(conn, "T_D_Min5")) {
                return 0;
            }
            return maxTime(conn, "T_D_Min5");
        } catch (SQLException e) {
            return 0;
        }
    }

    private static boolean hasTable(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * {@code months} 个月前的零点时刻（yyyyMMddHHmm）。
     *
     * <p>只给查缺补漏判「这只还活跃吗」用（本地数据来源分钟文件只滚动 6 个多月），
     * <b>不参与删数据</b> —— 写库那边一条老数据都不删。</p>
     */
    public static long monthsAgoCutoff(int months) {
        LocalDate date = LocalDate.now().minusMonths(Math.max(1, months));
        return date.getYear() * 100000000L
                + date.getMonthValue() * 1000000L
                + date.getDayOfMonth() * 10000L;
    }

    /**
     * 本地数据来源里的分钟线文件清单：{@code code → {lc5, lc1}}。
     *
     * <p>查缺补漏要按代码查「这只有没有分钟文件、本地数据来源到哪一天」，所以这里
     * <b>不按库存在与否过滤</b> —— 缺库的股票建完库马上也要补分钟线。</p>
     */
    public static Map<String, File[]> minuteFiles(File vipdoc) {
        return collectFiles(vipdoc, false);
    }

    /**
     * 扫 vipdoc 下三个市场的分钟线目录。
     *
     * <p>本地数据来源标准位置是 {@code minline/*.lc5}（5 分钟）和 {@code fzline/*.lc1}（1 分钟），
     * 但实际安装里见过把 .lc5 直接放在 {@code fzline} 下的（本机就是这样），
     * 所以两个目录都扫，按扩展名认周期，不要相信目录名。</p>
     *
     * <p><b>{@code onlyExistingDb} 时只收已经有日线库的代码</b>：本地数据来源的分钟线目录里混着指数（sh000001）、
     * 板块（sh880860）、基金（sh560250）、北交所（bj92xxxx），全收下来会给它们凭空造出一堆只有分钟表、
     * 没有日线表的库。本项目的脚本都跑在日线上，这些代码永远用不到，直接按「data/db 里有没有这个库」
     * 过滤最省事。</p>
     *
     * @return code → {lc5 文件, lc1 文件}，可能为 null
     */
    private static Map<String, File[]> collectFiles(File vipdoc, boolean onlyExistingDb) {
        Map<String, File[]> result = new LinkedHashMap<>();
        String dbPath = updater.mount.MountConfig.currentDbDir();
        File dbDir = onlyExistingDb && dbPath != null && !dbPath.isEmpty()
                ? new File(dbPath) : null;

        String[] markets = {"sh", "sz", "bj"};
        String[] dirs = {"minline", "fzline"};
        for (String market : markets) {
            for (String dir : dirs) {
                File folder = new File(new File(vipdoc, market), dir);
                if (!folder.isDirectory()) {
                    continue;
                }
                File[] list;
                try {
                    list = FileTool.listFilesBasic(folder.getPath());
                } catch (Exception e) {
                    Log.error("读取目录失败：" + folder + " " + e.getMessage());
                    continue;
                }
                for (File file : list) {
                    String name = file.getName().toLowerCase();
                    String code = TdxMinDataReader.codeOf(name);
                    if (code == null) {
                        continue;
                    }
                    boolean is5 = name.endsWith(".lc5");
                    boolean is1 = name.endsWith(".lc1");
                    if (!is5 && !is1) {
                        continue;
                    }
                    if (dbDir != null && !new File(dbDir, code + ".db").isFile()) {
                        continue;
                    }
                    File[] pair = result.get(code);
                    if (pair == null) {
                        pair = new File[2];
                        result.put(code, pair);
                    }
                    // minline 排在 fzline 前面扫，这里再判一次，保证 5 分钟优先
                    if (is5 && (pair[0] == null || dir.equals("minline"))) {
                        pair[0] = file;
                    } else if (is1 && pair[1] == null) {
                        pair[1] = file;
                    }
                }
            }
        }
        return result;
    }
}
