package updater.mount;

import updater.db.VaultDb;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 挂载数据的校验工具。
 *
 * <p>股票数据文件夹：里面只能有每只股票一个 db（不允许子文件夹、不允许其他文件），
 * 抽样打开几个库确认 T_D_Day 表存在。
 *
 * <p>其他价格数据 db：能按 SQLite 打开即可挂载；表定义遵循「挂载说明」里的数据接口规范。
 * 有除权表（T_Divdend）和没有除权表两种情况都算通过，只是提示不同。
 *
 * <p>股票名称库 db：必须有 stock_info(stock_code, stock_name) 表；空库/只读只给警告。
 */
public class MountCheck {

    /** 校验结果 */
    public static class Result {
        public boolean ok;
        /** 给用户看的结论（多行） */
        public final StringBuilder message = new StringBuilder();
        /** 警告（不阻止挂载） */
        public final List<String> warnings = new ArrayList<>();

        public String text() {
            return message.toString();
        }
    }

    /** 抽样校验的库数量上限 */
    private static final int SAMPLE_LIMIT = 3;

    /**
     * 校验股票数据文件夹。
     * 规则：目录存在、非空；里面只能有 *.db 文件；抽样库里有 T_D_Day 表。
     * T_Divdend 有/无都行（无则按不复权数据处理，界面会提示）。
     */
    public static Result checkStockDir(String dirPath) {
        Result result = new Result();
        if (dirPath == null || dirPath.trim().isEmpty()) {
            result.ok = false;
            result.message.append("请先选择股票数据文件夹");
            return result;
        }
        File dir = new File(dirPath.trim());
        if (!dir.isDirectory()) {
            result.ok = false;
            result.message.append("目录不存在或不是文件夹:\n").append(dirPath.trim());
            return result;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            result.ok = false;
            result.message.append("无法读取目录:\n").append(dir.getAbsolutePath());
            return result;
        }

        int dbCount = 0;
        List<File> nonDb = new ArrayList<>();
        List<File> subDirs = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) {
                subDirs.add(f);
            } else if (f.getName().toLowerCase().endsWith(".db")) {
                dbCount++;
            } else {
                nonDb.add(f);
            }
        }

        if (dbCount == 0) {
            result.ok = false;
            result.message.append("文件夹里没有 .db 文件。\n股票数据必须是每只股票一个 db（如 sh600000.db）");
            return result;
        }

        result.message.append("股票库数量: ").append(dbCount).append(" 个");

        if (!subDirs.isEmpty()) {
            result.ok = false;
            result.message.append("\n文件夹里有子文件夹（如 ").append(subDirs.get(0).getName())
                    .append("），股票数据文件夹只能放股票库文件");
            return result;
        }
        if (!nonDb.isEmpty()) {
            result.ok = false;
            result.message.append("\n文件夹里有非 .db 文件（如 ").append(nonDb.get(0).getName())
                    .append("），股票数据文件夹只能放股票库文件");
            return result;
        }

        // 抽样验证：T_D_Day 表必须存在；T_Divdend 可选（有/无/部分有都允许挂载）
        int checked = 0;
        int withDividend = 0;
        List<String> badSamples = new ArrayList<>();
        for (File f : files) {
            if (checked >= SAMPLE_LIMIT) {
                break;
            }
            if (!f.getName().toLowerCase().endsWith(".db")) {
                continue;
            }
            checked++;
            String err = probeStockDb(f);
            if (err != null) {
                badSamples.add(f.getName() + ": " + err);
            } else if (hasTable(f, "T_Divdend")) {
                withDividend++;
            }
        }
        if (!badSamples.isEmpty()) {
            result.ok = false;
            result.message.append("\n抽样校验失败:\n");
            for (String bad : badSamples) {
                result.message.append("  ").append(bad).append("\n");
            }
            result.message.append("每个库必须包含日线表 T_D_Day（表定义见挂载说明）");
            return result;
        }

        result.ok = true;
        if (withDividend == 0) {
            result.message.append("\n抽样 ").append(checked).append(" 个库都没有除权表 T_Divdend")
                    .append("（挂载时自动补空表，按不复权处理）");
        } else if (withDividend == checked) {
            result.message.append("\n抽样 ").append(checked).append(" 个库都包含除权表 T_Divdend");
        } else {
            result.message.append("\n抽样 ").append(checked).append(" 个库: ")
                    .append(withDividend).append(" 个有除权表、")
                    .append(checked - withDividend).append(" 个没有（没有的挂载时自动补空表）");
        }
        return result;
    }

    /**
     * 校验其他价格数据 db 文件。
     * 能按 SQLite 打开即通过；报告表清单；除权表有无都允许。
     */
    public static Result checkExtraDb(String filePath) {
        Result result = new Result();
        if (filePath == null || filePath.trim().isEmpty()) {
            result.ok = false;
            result.message.append("请选择 db 文件");
            return result;
        }
        File file = new File(filePath.trim());
        if (!file.isFile()) {
            result.ok = false;
            result.message.append("文件不存在:\n").append(filePath.trim());
            return result;
        }

        List<String> tables = new ArrayList<>();
        String err = listTables(file, tables);
        if (err != null) {
            result.ok = false;
            result.message.append("无法打开（不是有效的 SQLite 库？）:\n").append(err);
            return result;
        }

        result.ok = true;
        result.message.append("表: ").append(tables.isEmpty() ? "(空库)" : String.join(", ", tables));
        if (!hasTable(file, "T_Divdend")) {
            result.warnings.add("没有除权表 T_Divdend，按不复权数据处理");
        }
        return result;
    }

    // ------------------------------------------------------------------ 财务库

    /** 财务库行的抽样上限（库可能上千万行，封顶避免挂载卡住） */
    private static final int FINANCE_ROW_CAP = 1000000;

    /**
     * 校验财务数据 db 文件（财报 + 股本）。
     *
     * <p>规则：
     * <ul>
     *   <li>能按 SQLite 打开；</li>
     *   <li>必须有财报表 {@code T_D_Finance}，且含 {@code code} / {@code report_date} 两列
     *       （程序按这两列做主键查询；其余财务字段是动态列，不强制）；</li>
     *   <li>股本表 {@code T_D_ShareCapital} 可选 —— 缺了只影响换手率/股本相关功能，
     *       给警告不拦挂载。</li>
     * </ul>
     *
     * @return ok=false 表示不能挂载
     */
    public static Result checkFinanceDb(String filePath) {
        Result result = new Result();
        if (filePath == null || filePath.trim().isEmpty()) {
            // 财务库可以不挂：不挂就不读取任何财务数据
            result.ok = true;
            result.message.append("未选择财务库：财务 / 股本相关功能将没有数据");
            return result;
        }
        File file = new File(filePath.trim());
        if (!file.isFile()) {
            result.ok = false;
            result.message.append("文件不存在:\n").append(filePath.trim());
            return result;
        }

        List<String> tables = new ArrayList<>();
        String err = listTables(file, tables);
        if (err != null) {
            result.ok = false;
            result.message.append("无法打开（不是有效的 SQLite 库？）:\n").append(err);
            return result;
        }
        if (!tables.contains("T_D_Finance")) {
            result.ok = false;
            result.message.append("缺少财报表 T_D_Finance\n")
                    .append("库里有: ").append(tables.isEmpty() ? "(空库)" : String.join(", ", tables))
                    .append("\n建表语句见「挂载说明」");
            return result;
        }

        List<String> columns = new ArrayList<>();
        String colErr = listColumns(file, "T_D_Finance", columns);
        if (colErr != null) {
            result.ok = false;
            result.message.append("读取 T_D_Finance 列失败:\n").append(colErr);
            return result;
        }
        List<String> missing = new ArrayList<>();
        if (!containsIgnoreCase(columns, "code")) {
            missing.add("code");
        }
        if (!containsIgnoreCase(columns, "report_date")) {
            missing.add("report_date");
        }
        if (!missing.isEmpty()) {
            result.ok = false;
            result.message.append("T_D_Finance 缺列: ").append(String.join(", ", missing))
                    .append("\n（code=股票代码，report_date=报告期，两列缺一不可）");
            return result;
        }

        int rows = countRows(file, "T_D_Finance");
        result.ok = true;
        result.message.append("T_D_Finance: ")
                .append(rows >= FINANCE_ROW_CAP ? FINANCE_ROW_CAP + "+ 行" : rows + " 行")
                .append("，").append(columns.size()).append(" 列");
        if (rows == 0) {
            result.warnings.add("财报表 T_D_Finance 是空的，导入财报后才有数据");
        }
        if (columns.size() <= 2) {
            result.warnings.add("T_D_Finance 只有 code / report_date 两列，没有财务指标字段");
        }

        if (tables.contains("T_D_ShareCapital")) {
            result.message.append("\nT_D_ShareCapital: 有（股本 / 换手率可用）");
        } else {
            result.warnings.add("缺股本表 T_D_ShareCapital，换手率与股本相关功能不可用");
        }

        // 财务库会被「导入财报」「同步股本」写入，目录不可写时必须提前说清楚
        File parent = file.getParentFile();
        if (parent == null || !parent.canWrite() || !file.canWrite()) {
            result.warnings.add("目录/文件不可写：导入财报、同步股本会失败（只读挂载只能查不能更新）");
        }
        return result;
    }

    // ------------------------------------------------------------------ 名称库

    /** 名称库行数上限（只是显示用，封顶避免大库拖住挂载画面） */
    private static final int NAME_ROW_CAP = 1000000;

    /**
     * 校验股票名称库 db 文件（表 {@code stock_info}：代码 → 名称）。
     *
     * <p>规则：
     * <ul>
     *   <li>空路径 = 不挂载名称库，界面只显示股票代码（算通过）；</li>
     *   <li>文件不存在也算通过，只给警告 —— 首次启动会自动建表
     *       （否则用户没法挂一个“还没建”的新库）；</li>
     *   <li>能按 SQLite 打开，且必须有 {@code stock_info} 表、含
     *       {@code stock_code} / {@code stock_name} 两列；</li>
     *   <li>0 行只给警告（更新器可补数据）；不可写也只给警告（只读挂载只能查不能更新）。</li>
     * </ul>
     */
    public static Result checkNameDb(String filePath) {
        Result result = new Result();
        if (filePath == null || filePath.trim().isEmpty()) {
            result.ok = true;
            result.message.append("未选择名称库：界面只显示股票代码，不显示名称");
            return result;
        }
        File file = new File(filePath.trim());
        if (!file.isFile()) {
            result.ok = true;
            result.message.append("文件目前不存在:\n").append(filePath.trim());
            result.warnings.add("挂载后首次启动会自动建表；库里有多少行就显示多少名称，"
                    + "空库 = 不显示名称（名称要靠「更新股票名称」写入，或直接用已有名称库）");
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                result.warnings.add("目录也不存在，启动时会一并创建；路径拼错的话请重新选");
            }
            return result;
        }

        List<String> tables = new ArrayList<>();
        String err = listTables(file, tables);
        if (err != null) {
            result.ok = false;
            result.message.append("无法打开（不是有效的 SQLite 库？）:\n").append(err);
            return result;
        }
        if (!tables.contains("stock_info")) {
            // 空库放行：这是「还没建表」的新库，首次读取会自动建 stock_info
            if (tables.isEmpty()) {
                result.ok = true;
                result.message.append("空库：还没有任何表");
                result.warnings.add("首次使用会自动建 stock_info 表；名称要靠「更新股票名称」写入");
                return result;
            }
            result.ok = false;
            result.message.append("缺少名称表 stock_info\n")
                    .append("库里有: ").append(String.join(", ", tables))
                    .append("\n建表语句见「挂载说明」");
            return result;
        }

        List<String> columns = new ArrayList<>();
        String colErr = listColumns(file, "stock_info", columns);
        if (colErr != null) {
            result.ok = false;
            result.message.append("读取 stock_info 列失败:\n").append(colErr);
            return result;
        }
        List<String> missing = new ArrayList<>();
        if (!containsIgnoreCase(columns, "stock_code")) {
            missing.add("stock_code");
        }
        if (!containsIgnoreCase(columns, "stock_name")) {
            missing.add("stock_name");
        }
        if (!missing.isEmpty()) {
            result.ok = false;
            result.message.append("stock_info 缺列: ").append(String.join(", ", missing))
                    .append("\n（stock_code=股票代码，stock_name=名称，两列缺一不可）");
            return result;
        }

        int rows = countRows(file, "stock_info");
        result.ok = true;
        result.message.append("stock_info: ")
                .append(rows >= NAME_ROW_CAP ? NAME_ROW_CAP + "+ 行" : rows + " 行");
        if (rows == 0) {
            result.warnings.add("名称表是空的：界面不会显示任何名称，"
                    + "可用更新器的「更新股票名称」补齐");
        }
        File parent = file.getParentFile();
        if (parent == null || !parent.canWrite() || !file.canWrite()) {
            result.warnings.add("目录/文件不可写：更新股票名称会失败（只读挂载只能查不能更新）");
        }
        return result;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        for (String item : list) {
            if (item != null && item.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static String listColumns(File dbFile, String table, List<String> columns) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0");
            int count = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= count; i++) {
                columns.add(rs.getMetaData().getColumnName(i));
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 行数（封顶 FINANCE_ROW_CAP，避免千万级全表扫描拖住挂载） */
    private static int countRows(File dbFile, String table) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM (SELECT 1 FROM \"" + table
                    + "\" LIMIT " + FINANCE_ROW_CAP + ")");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 除权表补齐

    /**
     * 补表进度回调。
     *
     * @param done    已检查的库数
     * @param total   需要检查的库总数
     * @param created 其中补建了空除权表的库数
     */
    public interface Progress {
        void update(int done, int total, int created);
    }

    /**
     * 补表用的线程数：挂载要给日常操作让出核心，不然整机一起卡住。
     *
     * <ul>
     *   <li>9 核以上：用 70% 的核心；</li>
     *   <li>4~8 核：留 2 个核心给界面与后台；</li>
     *   <li>3 核及以下：只用 1 个线程，这种机器上并行只会让界面更卡。</li>
     * </ul>
     */
    static int workerCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores >= 9) {
            return Math.max(2, cores * 70 / 100);
        }
        if (cores >= 4) {
            return cores - 2;
        }
        return 1;
    }

    /**
     * 给文件夹里所有缺 T_Divdend 表的股票库补一张空表。
     *
     * <p>为什么必须补：程序查询日线/周线的 SQL 都引用 T_Divdend 做复权，
     * 库里没这张表会直接报「no such table」。补空表后 COALESCE 按 1 处理，
     * 等效不复权，正好符合「无除权数据」的语义。
     *
     * <p>为什么多线程：一个目录里常有几千个库，每个都要单独开一次 SQLite 连接，
     * 耗时取决于单文件打开成本（机械盘冷缓存、杀软实时扫描都会把它放大几十倍），
     * 串行跑下来用户只能干等。各线程处理不同的库文件，互不抢锁。
     *
     * @param progress 进度回调（可为 null）
     * @return [0]=补建的库数量, [1]=打开失败被跳过的数量
     */
    public static int[] fixMissingDividendTables(File dir, Progress progress) {
        File[] files = dir.listFiles();
        if (files == null) {
            return new int[]{0, 0};
        }
        List<File> dbs = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".db")) {
                dbs.add(f);
            }
        }
        final int total = dbs.size();
        if (total == 0) {
            return new int[]{0, 0};
        }

        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        // 每 1% 回调一次：几千个库逐个刷界面会把事件队列压满
        final int reportStep = Math.max(1, total / 100);

        int workers = Math.min(workerCount(), total);
        long start = System.currentTimeMillis();
        if (progress != null) {
            // 先把总数报出去：界面从「写入配置」切到「检查除权表 0/N」，进度条也才有满值
            progress.update(0, total, 0);
        }
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    int index;
                    while ((index = next.getAndIncrement()) < total) {
                        File file = dbs.get(index);
                        try {
                            if (ensureDividendTable(file)) {
                                created.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            System.out.println("补建除权表失败 " + file.getName() + ": " + e.getMessage());
                        }
                        int finished = done.incrementAndGet();
                        if (progress != null && (finished % reportStep == 0 || finished == total)) {
                            progress.update(finished, total, created.get());
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.out.println("除权表检查线程异常: " + e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (progress != null) {
            progress.update(total, total, created.get());
        }
        System.out.println("除权表检查完成: " + total + " 个库 / " + workers + " 线程 / "
                + (System.currentTimeMillis() - start) + " ms，补建 " + created.get()
                + " 个，失败 " + failed.get() + " 个");
        return new int[]{created.get(), failed.get()};
    }

    /**
     * 单个股票库：有 T_Divdend 就什么都不做，缺了补一张空表。
     *
     * <p>查和建共用一个连接：原来先开一次连接查、再开一次连接建，缺表时白多一倍文件打开。
     *
     * @return true = 补建了空表
     */
    private static boolean ensureDividendTable(File dbFile) throws Exception {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='T_Divdend'")) {
                if (rs.next()) {
                    return false;
                }
            }
            stmt.execute("CREATE TABLE IF NOT EXISTS T_Divdend"
                    + "(TIME BIGINT PRIMARY KEY NOT NULL, FACTOR FLOAT NOT NULL,"
                    + " TEXT CHAR(64) NOT NULL)");
            return true;
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 打开一个个股库，确认 T_D_Day 存在且能读出列名。
     *
     * @return null = 通过；否则返回错误描述
     */
    private static String probeStockDb(File dbFile) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='T_D_Day'");
            if (!rs.next()) {
                return "缺少日线表 T_D_Day";
            }
            ResultSet cols = stmt.executeQuery("SELECT * FROM T_D_Day LIMIT 1");
            int count = cols.getMetaData().getColumnCount();
            if (count < 7) {
                return "T_D_Day 列数不足（应为 TIME/OPEN/CLOSE/HIGH/LOW/VOL/AMO）";
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

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

    private static String listTables(File dbFile, List<String> tables) {
        try (Connection conn = VaultDb.openCompat(dbFile);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
