package updater.mount;

import updater.db.VaultDb;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据文件夹的固定结构：
 *
 * <pre>
 * &lt;dataRoot&gt;/
 *   db/sh600000.db …   个股行情库（每只股票一个，文件名固定）
 *   finance.db         财报 + 股本库
 *   stock_name.db      名称库
 * </pre>
 *
 * <p>目录结构与文件名都是固定的，界面上只让用户选「数据文件夹」这一个位置，
 * 不再单独指定 db 文件 —— 更新器与读库的一方都按这套结构读写，
 * 任何一处改结构都会让另两方读不到数据。</p>
 *
 * <p>识别只认标准位置，不做「上一级目录找财报库」「根目录也能放个股库」这类兜底：
 * 兜底越多，用户越容易把数据放到程序猜不到的地方。识别不清的地方写进
 * {@link Scan#notes}，原样显示给用户。</p>
 */
public final class DataRoot {

    /** 股票库的标准子目录名 */
    public static final String STOCK_SUBDIR = "db";
    /** 财报库的标准文件名 */
    public static final String FINANCE_FILE = "finance.db";
    /** 名称库的标准文件名 */
    public static final String NAME_FILE = "stock_name.db";

    /** 个股库：sh600000.db / sz000001.db / bj430047.db（大小写不敏感） */
    private static final Pattern STOCK_DB =
            Pattern.compile("^(sh|sz|bj)\\d{6}\\.db$", Pattern.CASE_INSENSITIVE);
    /** 也容忍不带前缀的 600000.db */
    private static final Pattern BARE_STOCK_DB =
            Pattern.compile("^\\d{6}\\.db$", Pattern.CASE_INSENSITIVE);

    private DataRoot() {
    }

    /** 识别结果 */
    public static final class Scan {
        /** 用户选的文件夹（绝对路径） */
        public String root = "";
        /** 目录本身可用（是文件夹） */
        public boolean ok;
        /** 目录不可用时的原因（多行） */
        public String error = "";
        /** 识别出的股票库目录（空 = 没找到） */
        public String stockDir = "";
        /** 该目录下个股库数量 */
        public int stockCount;
        /** 股票库是否在 db 子目录里 */
        public boolean stockInSubdir;
        /** 识别出的财报库（空 = 不挂财报） */
        public String financeDb = "";
        /** 识别出的名称库（空 = 不挂名称） */
        public String nameDb = "";
        /** 识别过程中的说明 / 提醒（照原文显示给用户） */
        public final List<String> notes = new ArrayList<>();

        public boolean hasStock() {
            return stockDir != null && !stockDir.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 识别

    /**
     * 识别一个数据文件夹（只认固定结构）。
     *
     * @param path 用户选择（或手输）的文件夹；null / 空 = 没选
     */
    public static Scan scan(String path) {
        Scan scan = new Scan();
        String text = path == null ? "" : path.trim();
        if (text.isEmpty()) {
            scan.error = "请先选择数据文件夹";
            return scan;
        }
        File root = new File(text).getAbsoluteFile();
        scan.root = root.getPath();
        if (!root.isDirectory()) {
            scan.error = "目录不存在或不是文件夹：\n" + scan.root;
            return scan;
        }
        scan.ok = true;

        // 1. 股票库：固定 <root>/db
        File sub = new File(root, STOCK_SUBDIR);
        scan.stockCount = countStockDbs(sub);
        if (scan.stockCount > 0) {
            scan.stockDir = sub.getPath();
            scan.stockInSubdir = true;
        } else if (sub.isDirectory()) {
            scan.notes.add("db 子目录里没有股票库：文件名应为 sh600000.db / sz000001.db / bj430047.db");
        } else {
            scan.notes.add("没有找到股票库：请在这个文件夹下建 db 子目录，每只股票一个 sh600000.db");
            if (countStockDbs(root) > 0) {
                scan.notes.add("当前文件夹根目录下有个股库，但结构固定要求放在 db 子目录里");
            }
        }

        // 2. 财报库：固定 <root>/finance.db
        File finance = new File(root, FINANCE_FILE);
        if (finance.isFile() && hasTable(finance, "T_D_Finance")) {
            scan.financeDb = finance.getPath();
        } else if (finance.isFile()) {
            scan.notes.add(FINANCE_FILE + " 里没有 T_D_Finance 表，没有使用它");
        } else {
            scan.notes.add("没有 " + FINANCE_FILE + " → 本次不挂财务数据");
        }

        // 3. 名称库：固定 <root>/stock_name.db
        File name = new File(root, NAME_FILE);
        if (name.isFile()) {
            if (hasTable(name, "stock_info")) {
                scan.nameDb = name.getPath();
            } else if (isEmptyDb(name)) {
                scan.nameDb = name.getPath();
                scan.notes.add(NAME_FILE + " 是空库（还没有 stock_info 表），首次使用会自建");
            } else {
                scan.notes.add(NAME_FILE + " 里没有 stock_info 表，没有使用它");
            }
        } else {
            scan.notes.add("没有 " + NAME_FILE + " → 界面只显示代码");
        }
        return scan;
    }

    /**
     * 从已挂载的股票库目录反推「数据文件夹」：{@code <root>/db} → {@code <root>}，
     * 其它情况原样返回。用于打开画面时把当前挂载回填到「数据文件夹」输入框。
     */
    public static String deriveRoot(String stockDir) {
        if (stockDir == null || stockDir.trim().isEmpty()) {
            return "";
        }
        File dir = new File(stockDir.trim());
        if (STOCK_SUBDIR.equalsIgnoreCase(dir.getName())) {
            File parent = dir.getParentFile();
            if (parent != null) {
                return parent.getPath();
            }
        }
        return dir.getPath();
    }

    // ------------------------------------------------------------------ 结构推导（与 scan 对称）

    /** 数据文件夹 → 股票库目录（固定 {@code <root>/db}） */
    public static String stockDirOf(String root) {
        if (root == null || root.trim().isEmpty()) {
            return "";
        }
        return new File(root.trim(), STOCK_SUBDIR).getPath();
    }

    /** 数据文件夹 → 财报库文件（固定 {@code <root>/finance.db}） */
    public static String financeFileOf(String root) {
        return new File(rootOf(root), FINANCE_FILE).getPath();
    }

    /** 数据文件夹 → 名称库文件（固定 {@code <root>/stock_name.db}） */
    public static String nameFileOf(String root) {
        return new File(rootOf(root), NAME_FILE).getPath();
    }

    private static String rootOf(String root) {
        if (root == null || root.trim().isEmpty()) {
            return ".";
        }
        return root.trim();
    }

    // ------------------------------------------------------------------ 内部

    /** 数一数目录里有多少个股库（只看文件名，不开库） */
    public static int countStockDbs(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isFile() && isStockDbName(file.getName())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isStockDbName(String name) {
        return name != null
                && (STOCK_DB.matcher(name).matches() || BARE_STOCK_DB.matcher(name).matches());
    }

    /** 库里有没有这张表（顺带验证能按 SQLite 打开） */
    private static boolean hasTable(File dbFile, String table) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    /** 空库：一个表都没有（0 字节新建文件也算） */
    private static boolean isEmptyDb(File dbFile) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master LIMIT 1");
            return !rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
