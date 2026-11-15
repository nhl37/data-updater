package updater.db;

import updater.fetchdata.NoDBFileException;
import updater.log.Log;
import updater.mount.MountConfig;

import java.io.File;
import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;

public class DBConnection {

    static {
        // 行情库最早被打开，这里是驱动注册的兜底：打包后 META-INF/services 可能没带上
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            Log.out("warn ##### SQLite 驱动未加载：" + e.getMessage());
        }
    }
    public  static final short CYCLE_DAY = 1;
    public  static final short CYCLE_WEEK = 2;
    public  static final short CYCLE_MONTH = 3;
    public  static final short CYCLE_QUARTER = 4;

    public  static final short QUERY_SQL_LIMIT_COUNT = 400;

    private static final ConcurrentHashMap<String, Connection> connMap = new ConcurrentHashMap<String, Connection>();


    /**
     * 个股库文件路径：只认「已挂载」的股票数据目录。
     *
     * <p>程序没有内置数据目录，所以未挂载时直接拒绝，不会退回到程序目录下的 data/db，
     * 也不会顺手创建目录或空库。</p>
     */
    public static String dbFilePath(String kCode) throws NoDBFileException {
        String dirPath = MountConfig.currentDbDir();
        if (dirPath.isEmpty()) {
            throw new NoDBFileException(MountConfig.NO_STOCK_DATA_MESSAGE);
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            throw new NoDBFileException("挂载的股票数据目录不存在: " + dir.getAbsolutePath());
        }
        return new File(dir, kCode + ".db").getAbsolutePath();
    }
    public static Connection connect(String kCode) throws DBException {
        return connect(kCode, false);
    }

    /**
     * 连接数据库
     * @param kCode 股票代码或数据库标识
     * @param createIfNotExist 如果数据库不存在是否创建
     * @return 数据库连接
     * @throws DBException
     */
    public static Connection connect(String kCode, boolean createIfNotExist) throws DBException {
        long methodStartTime = -1;
        if (Log.outDebugLog) {
            methodStartTime = Log.methodStart("DBConnection", "connect", "kCode: " + kCode);
        }
        
        try {
            // 获取数据库文件路径
            String dbPath = dbFilePath(kCode);
            File dbFile = new File(dbPath);
            
            // 检查数据库文件是否存在。
            // 这里原来还有一句 System.out.println("创建新的数据库文件: ...")：全市场首次跑
            // 会打 5000 多行，GUI 下每一行都要经 EDT 往日志区追加并 setCaretPosition，
            // 明显拖慢界面。建库数量已经在「新建库 N 个」的进度里体现了，不必逐只刷屏。
            if (!dbFile.exists() && !createIfNotExist) {
                throw new NoDBFileException("数据库文件不存在: " + dbPath);
            }
            
            // 创建数据库连接
            // 已有库走兼容打开：明文库照原样开、密文库自动带密钥。
            // 加密只影响文件本身，调用方拿到的仍是普通 Connection，5000 多处调用无需改动。
            //
            // 新建的库一律写成明文：这里开的是个股行情库，属于「能从数据源重新拿到」的数据，
            // 策略上就该明文（见 VaultDb.openPlain 注释）。走 openCompat 会因为它把
            // 0 字节文件当密文而建出一个密文库 —— 后果是用户拿 pandas 读不了、
            // 搬去别的机器还得带 vault.key，而本机其他 5000 多个库都是明文。
            boolean newFile = !dbFile.isFile() || dbFile.length() == 0;
            Connection conn = newFile
                    ? VaultDb.openPlain(dbFile)
                    : VaultDb.openCompat(dbFile);
            
            // 启用外键约束
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            
            if (Log.outDebugLog) {
                Log.methodEndDebugTime("DBConnection", "connect", "Connected to database: " + dbPath, methodStartTime);
            }
            
            return conn;
            
        } catch (SQLException e) {
            throw new DBException("Failed to connect to database: " + kCode, e);
        }
    }


    public static void close(String kCode) throws DBException {
        if(kCode == null) {
            return;
        }
        // 原子性地移除并关闭连接
        Connection conn = connMap.remove(kCode);
        if(conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // 记录日志但不抛出异常，因为这是清理操作
                e.printStackTrace();
                throw new DBException(e);
            }
        }
    }

    /**
     * 关掉调用方自己打开的连接。
     * <p>
     * {@link #connect(String)} 每次都会新开一个连接、并<b>不</b>放进 {@link #connMap}，
     * 所以 {@link #close(String)} 关不掉它。按股票批量读写（全市场扫描、盘后更新）时必须用
     * 这个方法，否则每只股票漏一个 SQLite 连接，几千只下来就是几万个连接、内存扛不住。
     */
    public static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            Log.out("warn ##### dbconn close error: " + e.getMessage());
        }
    }

    public static String getTableName(String kCode,short cycle) throws DBException {
        return switch (cycle) {
            case CYCLE_DAY -> "T_D_Day";
            case CYCLE_WEEK -> "T_D_Week";
            case CYCLE_MONTH -> "T_D_Month";
            case CYCLE_QUARTER -> "T_D_Quarter";
            default -> throw new RuntimeException("error cycle:" + cycle);
        };
    }

    public static void execute(Connection conn, String sql) throws DBException {
        Log.outSql("sql:" + sql);

        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            stmt.execute(sql);
            //conn.commit();;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    throw new DBException(e);
                }
            }

        }
    }

    public static void prepareExecute(Connection conn, String sql) throws DBException {
        Log.outSql("sql:" + sql);

        PreparedStatement pstmt = null;
        try {
            pstmt = conn.prepareStatement(sql);
            pstmt.executeUpdate();
            //conn.commit();


        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (pstmt != null) {
                try {
                    pstmt.close();
                } catch (SQLException e) {
                    throw new DBException(e);
                }
            }

        }
    }

}