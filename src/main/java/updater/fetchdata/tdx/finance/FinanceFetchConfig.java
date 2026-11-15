package updater.fetchdata.tdx.finance;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;

import java.io.File;

/**
 * 本地数据来源财报数据下载配置
 *
 * python 解释器优先复用除权除息脚本的虚拟环境配置 tdx.dividend.pythonExe，
 * 例如: tdx.dividend.pythonExe=~/.venv/fetch/bin/python
 */
public class FinanceFetchConfig {

    /** 复用除权脚本的解释器配置项 */
    public static final String KEY_PYTHON_EXE = updater.cfg.PythonInterpreter.KEY;
    /** 可选：仅为财报脚本单独指定解释器，不配置则复用 tdx.dividend.pythonExe */
    public static final String KEY_PYTHON_EXE_FINANCE = "tdx.finance.pythonExe";
    public static final String KEY_PYTHON_FILE = "tdx.finance.pythonFile";
    public static final String KEY_DOWNLOAD_DIR = "tdx.finance.downloadDir";
    public static final String KEY_CSV_FILE = "tdx.finance.csvFile";
    public static final String KEY_META_FILE = "tdx.finance.metaFile";
    /** 财报库目录(仅作为挂载值的镜像写在 config.properties 里，程序不以此定位) */
    public static final String KEY_DB_DIR = "tdx.finance.dbDir";
    public static final String KEY_DB_NAME = "tdx.finance.dbName";
    public static final String KEY_MIN_VALID_SIZE = "tdx.finance.minValidSize";
    public static final String KEY_CODE_PREFIX = "tdx.finance.codePrefix";
    public static final String KEY_DEBUG = "tdx.finance.debug";

    /** 系统 PATH 中的解释器兜底 */
    public static final String DEFAULT_PYTHON_EXE = "python3";

    private static FinanceFetchConfig instance;

    private final EnhancedConfigReader reader;

    private FinanceFetchConfig(EnhancedConfigReader reader) {
        this.reader = reader;
    }

    public static synchronized FinanceFetchConfig getInstance() throws ConfigException {
        if (instance == null) {
            instance = new FinanceFetchConfig(AppConfigManager.getInstance().getConfigReader());
        }
        return instance;
    }

    /**
     * python 解释器：优先 {@code tdx.finance.pythonExe}，否则交给
     * {@link updater.cfg.PythonInterpreter} —— 它先认 {@code tdx.dividend.pythonExe}
     * （用户能在「设置」里填），跑不起来再依次试 python3 / python / py。
     *
     * <p>以前这里在配置为空时回退到写死的虚拟环境路径
     * {@code ~/.venv/fetch/bin/python} —— 那是某台机器上的目录，别的机器上不存在，
     * 已经删掉；探测交给公共组件，别在各自类里再写一份。</p>
     */
    public String pythonExe() {
        String exe = trimToNull(reader.getString(KEY_PYTHON_EXE_FINANCE));
        if (exe != null) {
            return exe;
        }
        String resolved = updater.cfg.PythonInterpreter.resolve();
        return resolved == null ? DEFAULT_PYTHON_EXE : resolved;
    }

    /**
     * 当前解释器是否为虚拟环境(路径形态判断)，仅用于日志提示
     */
    public boolean isVirtualEnvPython() {
        String exe = pythonExe().replace('\\', '/');
        return exe.contains("/bin/python") || exe.contains("/Scripts/python");
    }

    /**
     * 配置的解释器文件；配置的是命令名(如 python3)时返回 null，交由系统 PATH 查找
     */
    public File pythonExeFile() {
        String exe = pythonExe();
        if (!exe.contains("/") && !exe.contains("\\")) {
            return null;
        }
        File file = new File(exe);
        if (!file.isAbsolute()) {
            file = updater.cfg.ProgramHome.resolve(exe);
        }
        return file;
    }

    public String pythonFile() {
        return reader.getString(KEY_PYTHON_FILE, "python/read_tdx_finance.py");
    }

    public String downloadDir() {
        String dir = trimToNull(reader.getString(KEY_DOWNLOAD_DIR));
        if (dir == null) {
            // 兜底落在程序目录的 data/finance_download；以前是开发机用户目录下那棵 ai/ 树，
            // 那是开发机的习惯路径，用户机器上会凭空多出一棵 ai 目录树
            dir = updater.cfg.ScriptPaths.workFile("data/finance_download").getPath();
        }
        return dir;
    }

    public String csvFile() {
        return reader.getString(KEY_CSV_FILE, "data/finance/tdx_finance.csv");
    }

    public String metaFile() {
        return reader.getString(KEY_META_FILE, "data/finance/tdx_finance.meta");
    }

    /**
     * 财报库名：仅用于旧调用兼容。挂载模式下实际库文件由 mount.properties 决定，
     * 程序不再根据目录 + 库名去拼路径。
     */
    public String dbName() {
        return reader.getString(KEY_DB_NAME, "finance");
    }

    public long minValidSize() {
        return reader.getLong(KEY_MIN_VALID_SIZE, 1024L);
    }

    public boolean codePrefix() {
        return reader.getBoolean(KEY_CODE_PREFIX, true);
    }

    public boolean debug() {
        return reader.getBoolean(KEY_DEBUG, false);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
