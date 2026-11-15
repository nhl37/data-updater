package updater.db;

import updater.log.Log;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 本地库的统一连接入口。
 *
 * <p>本程序只读写<b>明文</b> SQLite 库：行情个股库、财报库、名称库、F10 库、股本库、
 * 股东库、风险库、时点库、主升浪图库。这些数据都能从数据源重新拿到，也用得着拿 Excel
 * 或 pandas 自己打开，所以一律明文，不带任何加密通道。</p>
 *
 * <p>两种打开方式只是调用点的语义区分，行为一致：</p>
 * <ul>
 *   <li>{@link #openPlain} —— 日常写入路径；</li>
 *   <li>{@link #openCompat} —— 扫描/探测路径（挂载检查会挨个探几千个库），只读不建。</li>
 * </ul>
 *
 * <p>遇到非明文库（旧版本遗留的加密文件）不做任何转换，直接抛一条能看懂的错误：
 * 猜测算法或凭空造钥匙只会把数据弄坏，删掉重新获取是唯一安全的做法。</p>
 */
public final class VaultDb {

    /** SQLite 明文文件头 */
    private static final byte[] SQLITE_HEADER = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static {
        // 不能只依赖 META-INF/services 自动注册：发布包用脚本拼装，services 一旦没带上，
        // 所有 DriverManager.getConnection 都会报 "No suitable driver found"，界面全线「读取失败」
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            Log.out("warn ##### SQLite 驱动未加载：" + e.getMessage());
        }
    }

    private VaultDb() {
    }

    /** 打开库（按明文） */
    public static Connection openPlain(String path) throws SQLException {
        return openPlain(new File(path));
    }

    /** 打开库（按明文）；非明文的旧文件给出可读提示，不尝试转换 */
    public static Connection openPlain(File file) throws SQLException {
        File db = file.getAbsoluteFile();
        File dir = db.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new SQLException("无法创建目录：" + dir.getAbsolutePath());
        }
        if (db.isFile() && db.length() > 0 && !isPlaintext(db)) {
            throw new SQLException("这个库不是明文格式，本程序只读写明文库：" + db.getAbsolutePath()
                    + "\n请删掉该文件后重新获取一次数据。", "08006");
        }
        return DriverManager.getConnection("jdbc:sqlite:" + db.getPath());
    }

    /**
     * 探测用打开：与 {@link #openPlain(File)} 相同，单独留一个名字是为了让调用点说明
     * 「这里只探不建」，扫几千个库时不必每个都走一遍建目录逻辑。
     */
    public static Connection openCompat(File file) throws SQLException {
        return openPlain(file);
    }

    /** 文件是否是明文 SQLite 库（0 字节新文件不算） */
    public static boolean isPlaintext(File file) {
        if (!file.isFile() || file.length() < SQLITE_HEADER.length) {
            return false;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[SQLITE_HEADER.length];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < head.length) {
                return false;
            }
            for (int i = 0; i < head.length; i++) {
                if (head[i] != SQLITE_HEADER[i]) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
