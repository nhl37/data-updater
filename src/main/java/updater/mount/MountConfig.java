package updater.mount;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigBackupManager;
import updater.cfg.ConfigFileEditor;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;
import updater.finance.FinanceRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 挂载数据配置 - 单例
 *
 * <p>程序没有「内置数据」：所有行情、财务数据都必须由用户挂载。
 * <b>未挂载 = 没有数据</b>，任何读取都按「没有数据」处理，不会回落到程序目录下的
 * data 文件夹，也不存在默认股票库或默认财报库。
 *
 * <p>挂载状态保存在程序根目录的 {@code mount.properties}，这是程序判定数据的唯一依据。
 * 保存挂载时同时把 {@code data.path.db} 与 {@code tdx.finance.*} 写回
 * {@code config.properties}，只是给外部脚本或人工查看的镜像，程序自身不再读这些键。
 *
 * <p>只配置一个「数据文件夹」，目录结构与文件名固定（见 {@link DataRoot}）：
 * <ul>
 *   <li>{@code db/} —— 个股库，每只股票一个（sh600000.db 这种）</li>
 *   <li>{@code finance.db} —— 财报 + 股本库；文件不存在 = 不读财务数据</li>
 *   <li>{@code stock_name.db} —— 名称库（stock_info）；首次使用会自动建表</li>
 * </ul>
 *
 * <p>数据文件夹与备份文件夹都记在 mount.properties；数据文件夹同时镜像到
 * config.properties 的 {@code data.path.db}，备份文件夹写入 {@code data.path.dbbak}
 * （数据更新器按这个键找备份位置）。财务库的 {@code tdx.finance.*} 镜像只给外部脚本看，
 * 程序自身不再读它。</p>
 *
 * <p>注意：挂载状态写进 mount.properties 后，建议<b>重启程序</b>再使用，
 * 让已经打开的画面、缓存和外部脚本都按新的数据位置运行。
 */
public class MountConfig {

    /** 未挂载股票数据时的统一提示 */
    public static final String NO_STOCK_DATA_MESSAGE =
            "尚未指定数据文件夹：请用 --data-dir 指定，或放到程序目录下的 data";
    /** 未挂载财务数据时的统一提示 */
    public static final String NO_FINANCE_DATA_MESSAGE =
            "尚未指定数据文件夹：请用 --data-dir 指定，且文件夹里需有 finance.db";

    private static final String MOUNT_FILE = "mount.properties";
    private static final String CONFIG_FILE = "config.properties";

    /** config.properties 里股票数据目录的键 */
    private static final String KEY_DB_PATH = "data.path.db";
    /** mount.properties 里是否处于挂载状态 */
    private static final String KEY_MOUNTED = "mount.enabled";

    /** mount.properties 里的数据文件夹（固定结构的根，db/、finance.db、stock_name.db 都在它下面） */
    private static final String KEY_DATA_ROOT = "mount.data.root";
    private static final String KEY_STOCK_DIR = "mount.stock.dir";
    /** 旧版本单独挂载的财务库文件（仅用于读取老配置，数据文件夹模式按固定结构推导） */
    private static final String KEY_FINANCE_DB = "mount.finance.file";
    /** 旧版本单独挂载的股票名称库文件（仅用于读取老配置） */
    private static final String KEY_NAME_DB = "mount.name.file";
    /** 备份文件夹（config.properties，与数据更新器共用） */
    private static final String KEY_BACKUP_DIR = AppConfigManager.KEY_DBBAKPath;

    /** config.properties 里财报库的两个键（目录 + 库名，拼接成 <dir>/<name>.db） */
    private static final String CFG_FINANCE_DIR = "tdx.finance.dbDir";
    private static final String CFG_FINANCE_NAME = "tdx.finance.dbName";

    /** 单例 */
    private static MountConfig instance;

    /**
     * 「数据文件夹」覆盖（供外部数据程序使用）。
     *
     * <p>外部数据程序是独立进程，它有自己的数据文件夹，<b>不该被调用方的 mount.properties
     * 牵着走</b>（那份配置可能指向网络盘、也可能压根没挂载）。启动时由该程序调用
     * {@link #setDataRootOverride(String)} 设进来，之后 {@link #currentDbDir()}、
     * {@link #currentNameDbFile()}、{@link #currentFinanceDbFile()} 全按它推导，
     * 整条更新链路（几十处调用）无需改动。</p>
     *
     * <p>调用方从不设置它，所以调用方的语义仍然是「只认挂载值」。</p>
     */
    private static volatile String dataRootOverride = "";

    /**
     * 设置数据文件夹覆盖：空串 / null = 取消覆盖，回到 mount.properties 的挂载值。
     *
     * <p>只设值、不建目录 —— 建目录是数据更新器自己的事（见 UpdaterDataDir#ensure）。</p>
     */
    public static void setDataRootOverride(String root) {
        dataRootOverride = root == null ? "" : root.trim();
    }

    /** 当前的数据文件夹覆盖值（空 = 没有覆盖，正常走挂载） */
    public static String dataRootOverride() {
        return dataRootOverride;
    }

    /** 是否处于「数据更新器自己的数据文件夹」模式 */
    public static boolean isDataRootOverridden() {
        return !dataRootOverride.isEmpty();
    }

    /** 是否已挂载（配置过数据文件夹） */
    private boolean mounted;
    /** 数据文件夹（固定结构的根；空 = 老配置没有这一项） */
    private String dataRoot = "";
    /** 挂载的股票数据文件夹（空 = 未设置；正常等于 {@code <dataRoot>/db}） */
    private String stockDir = "";
    /** 旧配置的财务库文件（数据文件夹模式下按固定结构推导，不读它） */
    private String financeDbFile = "";
    /** 旧配置的股票名称库文件（数据文件夹模式下按固定结构推导，不读它） */
    private String nameDbFile = "";

    public static synchronized MountConfig getInstance() {
        if (instance == null) {
            instance = new MountConfig();
            instance.load();
        }
        return instance;
    }

    // ------------------------------------------------------------------ 读取

    /** 从 mount.properties 载入当前挂载状态 */
    public synchronized void load() {
        Properties props = new Properties();
        File file = new File(MOUNT_FILE);
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                // 自家配置文件，固定 UTF-8，避免 Properties 默认 ISO-8859-1 丢中文
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.out.println("mount.properties 读取失败: " + e.getMessage());
            }
        }

        mounted = "true".equalsIgnoreCase(props.getProperty(KEY_MOUNTED, "false"));
        stockDir = nullToEmpty(props.getProperty(KEY_STOCK_DIR));
        financeDbFile = nullToEmpty(props.getProperty(KEY_FINANCE_DB));
        nameDbFile = nullToEmpty(props.getProperty(KEY_NAME_DB));
        dataRoot = nullToEmpty(props.getProperty(KEY_DATA_ROOT));
        if (dataRoot.isEmpty() && !stockDir.isEmpty()) {
            // 老配置只有股票库目录：反推数据文件夹，升级后按固定结构继续工作
            dataRoot = DataRoot.deriveRoot(stockDir);
        }
    }

    // ------------------------------------------------------------------ 保存

    /** 把当前挂载状态写回 mount.properties（不改动 config.properties） */
    public synchronized void save() throws IOException {
        Properties props = new Properties();
        props.setProperty(KEY_MOUNTED, String.valueOf(mounted));
        props.setProperty(KEY_DATA_ROOT, dataRoot);
        props.setProperty(KEY_STOCK_DIR, stockDir);
        props.setProperty(KEY_FINANCE_DB, financeDbFile);
        props.setProperty(KEY_NAME_DB, nameDbFile);

        File file = new File(MOUNT_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                    "mount config (UTF-8)");
        }
    }

    /**
     * 挂载生效：数据文件夹按固定结构推导出股票库 / 财报库 / 名称库，
     * 写 mount.properties + config.properties 镜像，然后给缺 T_Divdend 表的库
     * 补空表（程序的查询 SQL 都引用除权表，缺表会报错；空表按因子 1 处理，
     * 等效不复权）。调用前请先用 {@link MountCheck} 校验。
     *
     * @param newDataRoot 数据文件夹（结构固定，见 {@link DataRoot}）
     * @param newBackupDir 备份文件夹；空串 = 未设置（数据更新器下载前会提醒）
     * @param progress    补表进度回调（可为 null）
     * @return [0]=补建了空除权表的库数量, [1]=打开失败被跳过的数量
     */
    public synchronized int[] applyMount(String newDataRoot, String newBackupDir,
                                         MountCheck.Progress progress)
            throws IOException, ConfigException {
        this.dataRoot = normalizePath(newDataRoot);
        this.stockDir = DataRoot.stockDirOf(this.dataRoot);
        this.financeDbFile = DataRoot.financeFileOf(this.dataRoot);
        this.nameDbFile = DataRoot.nameFileOf(this.dataRoot);
        this.mounted = !this.dataRoot.isEmpty();

        // 先写配置（保证挂载状态一致），再补表（数据层，尽力而为，失败会在返回信息里提示）
        writeConfigDbPath(this.stockDir);
        writeFinanceDbPath(this.financeDbFile);
        writeBackupDir(newBackupDir);
        save();

        if (!this.stockDir.isEmpty()) {
            return MountCheck.fixMissingDividendTables(new File(this.stockDir), progress);
        }
        return new int[]{0, 0};
    }

    /**
     * 恢复「什么都没挂载」的初始状态（调试用）。
     *
     * <p>做的事：</p>
     * <ol>
     *   <li>把 config.properties 与 mount.properties 各备份一份 {@code .bak_yyyyMMdd_HHmmss}</li>
     *   <li>config.properties 的 data.path.db 与 tdx.finance.dbDir / tdx.finance.dbName
     *       清空（不留任何默认目录）</li>
     *   <li>删除 mount.properties（挂载标记、股票目录、其他价格数据、财报库覆盖、
     *       名称库覆盖一起消失，名称库变为未挂载 —— 界面不显示名称）</li>
     *   <li>清空内存字段，并让配置快照与财报缓存立刻失效</li>
     * </ol>
     *
     * <p>只改配置指向，<b>不删除任何数据文件</b>，也不回滚挂载时补过的空除权表。</p>
     *
     * @return 人类可读的操作记录（逐行显示在结果框里）
     */
    public synchronized List<String> resetToUnmounted() throws IOException, ConfigException {
        List<String> report = new ArrayList<>();

        File configFile = new File(CONFIG_FILE);
        File mountFile = new File(MOUNT_FILE);

        // 1. 先备份，改坏了还能手工还原
        if (configFile.isFile()) {
            File bak = new ConfigBackupManager(configFile).backupCurrent();
            report.add("已备份 " + CONFIG_FILE + " → " + bak.getName());
        }
        if (mountFile.isFile()) {
            File bak = copyToBackup(mountFile);
            report.add("已备份 " + MOUNT_FILE + " → " + bak.getName());
        }

        // 2. 股票数据目录清空：没有挂载就没有数据，不留默认目录
        String oldDbDir = readConfigDbPath();
        writeConfigKey(KEY_DB_PATH, "");
        report.add(KEY_DB_PATH + ": " + (oldDbDir.isEmpty() ? "(空)" : oldDbDir)
                + " → (已清空，不读取任何股票数据)");

        // 3. 财报库配置清空
        String oldFinanceDir = readConfigValue(CFG_FINANCE_DIR);
        String oldFinanceName = readConfigValue(CFG_FINANCE_NAME);
        writeConfigKey(CFG_FINANCE_DIR, "");
        writeConfigKey(CFG_FINANCE_NAME, "");
        report.add(CFG_FINANCE_DIR + ": " + (oldFinanceDir.isEmpty() ? "(空)" : oldFinanceDir)
                + " → (已清空)");
        report.add(CFG_FINANCE_NAME + ": " + (oldFinanceName.isEmpty() ? "(空)" : oldFinanceName)
                + " → (已清空，不读取任何财务数据)");

        // 4. 名称库取消挂载（只清覆盖值，不动文件本身）
        String oldNameDb = nameDbFile;
        report.add(KEY_NAME_DB + ": " + (oldNameDb.isEmpty() ? "(未挂载)" : oldNameDb)
                + " → (未挂载，不显示股票名称)");

        // 5. 删掉 mount.properties（等价于「从没挂载过」）
        if (mountFile.isFile()) {
            if (!mountFile.delete()) {
                throw new IOException("删除失败: " + mountFile.getAbsolutePath());
            }
            report.add("已删除 " + MOUNT_FILE + "（" + mountFile.getAbsolutePath() + "）");
        } else {
            report.add(MOUNT_FILE + " 本来就不存在，无需删除");
        }

        // 6. 内存状态清空
        mounted = false;
        dataRoot = "";
        stockDir = "";
        financeDbFile = "";
        nameDbFile = "";
        report.add("内存状态已清空：未挂载 / 不读取财报与名称库");

        // 7. 让改动立即生效（尽力而为，失败只记一行，不中断）
        try {
            AppConfigManager manager = AppConfigManager.getInstance();
            EnhancedConfigReader reader = manager.getConfigReader();
            if (reader == null) {
                manager.loadConfig(configFile.getPath());
            } else {
                reader.reload();
            }
            report.add("已重新载入配置快照，当前数据目录: " + currentDbDir());
        } catch (Exception e) {
            report.add("重新载入配置失败（重启程序后生效）: " + e.getMessage());
        }
        try {
            FinanceRepository.clearCache();
            report.add("已清空财报缓存");
        } catch (Exception e) {
            report.add("清空财报缓存失败（可忽略）: " + e.getMessage());
        }
        return report;
    }

    // ------------------------------------------------------------------ 状态查询

    public synchronized boolean isMounted() {
        return mounted;
    }

    public synchronized String getStockDir() {
        return stockDir;
    }

    /** 数据文件夹（固定结构的根；老配置由股票库目录反推） */
    public synchronized String getDataRoot() {
        return dataRoot;
    }

    /** 挂载的财务库 db 文件（空 = 未挂载，程序不读取任何财务数据） */
    public synchronized String getFinanceDbFile() {
        return financeDbFile;
    }

    /** 挂载的股票名称库 db 文件（空 = 未挂载，全程序不显示股票名称） */
    public synchronized String getNameDbFile() {
        return nameDbFile;
    }

    /**
     * 当前生效的股票名称库文件：<b>只认挂载值，未挂载返回空串</b>。
     *
     * <p>名称库是「挂载才启用」——没有挂就不显示任何名称（界面只有代码），
     * 不再回落到程序目录下的某个默认库。</p>
     *
     * <p>全工程的名称读写都经 {@code updater.name.StockNameStore.dbFile()}，
     * 那里会把空串转成 null，所以挂载只改这一处，读取方零改动。</p>
     */
    public static String currentNameDbFile() {
        String root = currentDataRoot();
        if (!root.isEmpty()) {
            return DataRoot.nameFileOf(root);
        }
        try {
            return MountConfig.getInstance().getNameDbFile();
        } catch (Exception e) {
            System.out.println("读取名称库挂载配置失败，按未挂载处理: " + e.getMessage());
            return "";
        }
    }

    /**
     * 当前生效的财务库文件：<b>只认挂载值</b>。
     *
     * <p>没挂财务库就返回空串，读取方应当按「没有财务数据」处理，
     * 不再回落到 config.properties 的 tdx.finance.* 或程序目录下的 data/finance.db。</p>
     */
    public static String currentFinanceDbFile() {
        String root = currentDataRoot();
        if (!root.isEmpty()) {
            String file = DataRoot.financeFileOf(root);
            // 固定结构下财报库就在 <root>/finance.db；文件还没建时按「没有财务数据」处理，
            // 更新器建好之后下一次读取自动就能用，不需要重新挂载
            return new File(file).isFile() ? file : "";
        }
        try {
            MountConfig current = MountConfig.getInstance();
            return current.isMounted() ? current.getFinanceDbFile() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前配置（含挂载覆盖）下的股票数据目录里有没有个股 db。
     * 主菜单据此决定挂载画面是「引导模式」还是「修改模式」。
     */
    public static boolean hasStockData() {
        String dir = currentDbDir();
        return !dir.isEmpty() && DataRoot.countStockDbs(new File(dir)) > 0;
    }

    /** 当前生效的股票数据目录：未挂载返回空串 */
    public static String currentDbDir() {
        String root = currentDataRoot();
        if (!root.isEmpty()) {
            return DataRoot.stockDirOf(root);
        }
        try {
            MountConfig current = MountConfig.getInstance();
            if (!current.isMounted()) {
                return "";
            }
            return current.getStockDir();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前生效的数据文件夹：更新器覆盖值优先，其次挂载的数据文件夹；
     * 未挂载返回空串（不回落到程序目录）。
     */
    public static String currentDataRoot() {
        if (!dataRootOverride.isEmpty()) {
            return dataRootOverride;
        }
        try {
            MountConfig current = MountConfig.getInstance();
            if (!current.isMounted()) {
                return "";
            }
            return current.getDataRoot();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 备份文件夹

    /** 配置的备份文件夹（config.properties 的 data.path.dbbak；空 = 未设置） */
    public static String configuredBackupDir() {
        try {
            String value = AppConfigManager.getInstance().getConfigReader()
                    .getString(KEY_BACKUP_DIR, "");
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 把备份文件夹写进 config.properties（空串 = 清空） */
    public static void saveBackupDir(String path) throws IOException, ConfigException {
        writeBackupDir(path);
    }

    private static void writeBackupDir(String path) throws IOException, ConfigException {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        writeConfigKey(KEY_BACKUP_DIR, value);
    }

    // ------------------------------------------------------------------ config.properties 读写

    /** 读 config.properties 里 data.path.db 的当前值（去引号后） */
    private static String readConfigDbPath() throws IOException, ConfigException {
        return readConfigValue(KEY_DB_PATH);
    }

    /** 把 data.path.db 写进 config.properties（ConfigFileEditor 保留注释与顺序） */
    private static void writeConfigDbPath(String value) throws IOException, ConfigException {
        writeConfigKey(KEY_DB_PATH, value);
    }

    /**
     * 把财务库文件拆成「目录 + 库名」写进 config.properties。
     *
     * <p>读取方（FinanceDBManager.resolveDbFile）是 {@code <dir>/<name>.db} 的拼法，
     * 所以 name 要去掉 .db 后缀；目录统一用 / 分隔，Windows 的 \ 会让配置读取混乱。
     */
    private static void writeFinanceDbPath(String dbFile) throws IOException, ConfigException {
        File file = new File(normalizePath(dbFile));
        String dir = file.getParent();
        if (dir == null || dir.trim().isEmpty()) {
            dir = ".";
        }
        writeConfigKey(CFG_FINANCE_DIR, dir.replace('\\', '/'));
        writeConfigKey(CFG_FINANCE_NAME, stripDbSuffix(file.getName()));
    }

    /** 通用写法：存在就改值，不存在就追加（注释与顺序由 ConfigFileEditor 保留） */
    private static void writeConfigKey(String key, String value) throws IOException, ConfigException {
        File file = new File(CONFIG_FILE);
        if (!file.isFile()) {
            throw new IOException("找不到配置文件: " + new File(CONFIG_FILE).getAbsolutePath());
        }
        ConfigFileEditor editor = new ConfigFileEditor(file);
        editor.load();
        if (editor.getEntries().stream().noneMatch(e -> key.equals(e[0]))) {
            editor.addEntry(key, value);
        } else {
            editor.setValue(key, value);
        }
        editor.save();
    }

    /** 读 config.properties 里某个键的当前值（去引号后），不存在返回空串 */
    private static String readConfigValue(String key) throws IOException, ConfigException {
        File file = new File(CONFIG_FILE);
        if (!file.isFile()) {
            throw new IOException("找不到配置文件: " + new File(CONFIG_FILE).getAbsolutePath());
        }
        ConfigFileEditor editor = new ConfigFileEditor(file);
        editor.load();
        for (String[] entry : editor.getEntries()) {
            if (key.equals(entry[0])) {
                return entry[1] == null ? "" : entry[1].trim();
            }
        }
        return "";
    }

    /** 把文件复制成 {@code 原名.bak_yyyyMMdd_HHmmss}，返回备份文件 */
    private static File copyToBackup(File source) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File bak = new File(source.getAbsolutePath() + ".bak_" + stamp);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(bak)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();
        }
        return bak;
    }

    /** 去掉 .db 后缀（大小写不敏感） */
    private static String stripDbSuffix(String name) {
        String text = name == null ? "" : name.trim();
        if (text.toLowerCase().endsWith(".db")) {
            return text.substring(0, text.length() - 3);
        }
        return text;
    }

    /** 路径规范化：去重多余分隔符与 ./ ..（手输路径常见），统一成绝对路径 */
    private static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return new File(path.trim()).toPath().normalize().toFile().getAbsolutePath();
    }

    // ------------------------------------------------------------------ 工具

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
