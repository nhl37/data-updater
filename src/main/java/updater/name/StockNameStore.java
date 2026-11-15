package updater.name;

import updater.db.VaultDb;
import updater.mount.MountConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 股票名称库 —— 全工程读写「代码 → 名称」的唯一入口。
 *
 * <p>名称原来和主升浪图片混在 {@code data/main_wave_images.db} 的 stock_info 表里，
 * 现在单独成库，并且是<b>「挂载才启用」</b>：在「挂载数据」画面里指定一个 db 文件
 * （见 {@link MountConfig#currentNameDbFile()}）才会读名称；</p>
 *
 * <p><b>没挂载 = 没有名称</b>。不存在任何“回落到程序自带数据”的兜底 ——
 * 不读旧库、不读 CSV、不内置一份名称表：界面、K线标题、脚本测试、
 * 主升浪、因子打分全都只显示股票代码。</p>
 *
 * <p>名称数据<b>全部在外挂的那个库里</b>：挂的是什么库、库里有哪些行，
 * 界面就显示什么。空库 = 没有名称。</p>
 *
 * <p>表结构与旧表逐字一致，所以旧库文件可以直接当名称库挂进来（那是“挂载”，
 * 不是程序自己去读旧库）。</p>
 */
public final class StockNameStore {

    /** 表名（与旧 stock_info 一致，方便直接接管已有库） */
    public static final String TABLE = "stock_info";

    public static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "stock_code VARCHAR(20) NOT NULL UNIQUE, "
                    + "stock_name VARCHAR(100), "
                    + "last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "data_source VARCHAR(50), "
                    + "created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")";

    /** 建表 + 首次导入只做一次 */
    private static boolean ready;

    /** “未挂载名称库”的提示只打一次，避免每轮评分刷一行日志 */
    private static boolean warnedUnmounted;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("未找到 SQLite 驱动，股票名称功能不可用: " + e.getMessage());
        }
    }

    private StockNameStore() {
    }

    /**
     * 是否挂载了名称库。
     *
     * <p>名称库是「挂载才启用」：没挂就不显示任何股票名称（各处只显示代码），
     * 不存在“回落到程序自带某个库”的兜底。</p>
     */
    public static boolean enabled() {
        return !mountedPath().isEmpty();
    }

    /** 当前生效的名称库文件；<b>未挂载时返回 null</b>，调用方必须先判空 */
    public static File dbFile() {
        String path = mountedPath();
        return path.isEmpty() ? null : new File(path).getAbsoluteFile();
    }

    /** 挂载配置里的名称库路径；未挂载（或读配置失败）返回空串 */
    private static String mountedPath() {
        String path;
        try {
            path = MountConfig.currentNameDbFile();
        } catch (Exception e) {
            System.err.println("读取名称库挂载配置失败，按未挂载处理: " + e.getMessage());
            return "";
        }
        return path == null ? "" : path.trim();
    }

    /**
     * 打开名称库连接（父目录不存在会先建出来）。
     *
     * <p>未挂载时返回一个<b>内存空库</b>（里面已建好空的 stock_info 表）：
     * 所有读取拿到的都是 0 行，所有写入落在内存里随即消失。这样上层的
     * {@code StockInfoDAO} 与各处调用方不用逐个加判空，也不会因为
     * “没挂名称库”而抛异常刷错误日志 —— 效果就是只显示代码。</p>
     */
    public static Connection open() throws SQLException {
        File file = dbFile();
        if (file == null) {
            return emptyConnection();
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            // 目录建不出来时交给 DriverManager 报错，错误信息里带路径更好排查
            parent.mkdirs();
        }
        // 名称库是下载来的数据，明文；老密文库由 openPlain 自动转成明文
        return VaultDb.openPlain(file);
    }

    /** 未挂载时用的内存空库（只有表结构、永远 0 行） */
    private static Connection emptyConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    /**
     * 确保名称库可用：<b>未挂载直接返回</b>（不建文件、不迁移）；挂了只建表，
     * <b>不往里填任何数据</b> —— 库里有多少行就显示多少名称，空库就是没有名称。
     *
     * <p>幂等 —— 同一个 JVM 里只做一次；任何失败只记日志不抛，避免把
     * 主升浪画面 / K 线图整体拖挂（它们只是拿不到名称，不该打不开）。</p>
     */
    public static synchronized void ensureReady() {
        if (ready) {
            return;
        }
        if (!enabled()) {
            // 未挂载：不建文件、不迁移、不读旧库。日志只打一次（FactorEngine 每轮评分都会 new 一个 DAO）
            if (!warnedUnmounted) {
                warnedUnmounted = true;
                System.out.println("未挂载股票名称库：界面只显示股票代码"
                        + "（可在「挂载数据」里指定 stock_name.db）");
            }
            return;
        }
        // 只尝试一次：只读挂载 / 磁盘异常时不要每次 new StockInfoDAO() 都重试一遍刷日志
        ready = true;
        try (Connection conn = open();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            System.out.println("股票名称库: " + dbFile().getAbsolutePath() + "（" + count(conn) + " 条）");
        } catch (Exception e) {
            System.err.println("股票名称库初始化失败: " + e.getMessage());
        }
    }

    private static int count(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
