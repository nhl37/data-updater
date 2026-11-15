package updater.fetchdata.capital;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;

import java.io.File;

/**
 * AKShare 股本 / 股东数据获取的配置。
 * <p>
 * python 解释器默认复用财报脚本的解释器（tdx.finance.pythonExe ->
 * tdx.dividend.pythonExe -> 虚拟环境），因为 akshare 就装在同一个虚拟环境里。
 * 想换解释器时单独配 akshare.pythonExe，不影响财报那条链。
 */
public final class AkshareCapitalConfig {

    /** 可选：单独指定 akshare 用的解释器 */
    public static final String KEY_PYTHON_EXE = "akshare.pythonExe";
    /** 抓数脚本，默认 python/akshare_capital.py */
    public static final String KEY_PYTHON_FILE = "akshare.capital.pythonFile";
    /** 结果 CSV，默认 data/capital/akshare_capital.csv */
    public static final String KEY_OUT_FILE = "akshare.capital.outFile";
    /** 待获取代码清单，默认 data/capital/codes.txt */
    public static final String KEY_CODES_FILE = "akshare.capital.codesFile";
    /** 并发线程数，默认 6 */
    public static final String KEY_THREADS = "akshare.capital.threads";
    /** 单次最多抓多少只，0 表示不限（用来自动更新时限制耗时） */
    public static final String KEY_MAX_CODES = "akshare.capital.maxCodes";

    public static final String DEFAULT_PYTHON_FILE = "python/akshare_capital.py";
    public static final String DEFAULT_OUT_FILE = "data/capital/akshare_capital.csv";
    public static final String DEFAULT_CODES_FILE = "data/capital/codes.txt";
    public static final int DEFAULT_THREADS = 6;
    /** 自动更新时默认最多抓 300 只，避免更新财报时等太久 */
    public static final int DEFAULT_MAX_CODES = 300;

    private static AkshareCapitalConfig instance;

    private final EnhancedConfigReader reader;

    private AkshareCapitalConfig(EnhancedConfigReader reader) {
        this.reader = reader;
    }

    public static synchronized AkshareCapitalConfig getInstance() throws ConfigException {
        if (instance == null) {
            instance = new AkshareCapitalConfig(AppConfigManager.getInstance().getConfigReader());
        }
        return instance;
    }

    /**
     * 解释器：{@code akshare.pythonExe} -> {@code tdx.finance.pythonExe} -> 自动探测
     * （{@code tdx.dividend.pythonExe} -> python3 -> python -> py）。
     *
     * <p>写死开发机虚拟环境路径的那段已经删掉：用户机器上没有那个目录，
     * 探测交给 {@link updater.cfg.PythonInterpreter} 统一做。</p>
     */
    public String pythonExe() {
        String exe = text(KEY_PYTHON_EXE);
        if (exe != null) {
            return exe;
        }
        exe = text("tdx.finance.pythonExe");
        if (exe != null) {
            return exe;
        }
        String resolved = updater.cfg.PythonInterpreter.resolve();
        return resolved == null ? "python3" : resolved;
    }

    public String pythonFile() {
        String value = text(KEY_PYTHON_FILE);
        return value == null ? DEFAULT_PYTHON_FILE : value;
    }

    public String outFile() {
        String value = text(KEY_OUT_FILE);
        return value == null ? DEFAULT_OUT_FILE : value;
    }

    public String codesFile() {
        String value = text(KEY_CODES_FILE);
        return value == null ? DEFAULT_CODES_FILE : value;
    }

    public int threads() {
        return intValue(KEY_THREADS, DEFAULT_THREADS);
    }

    public int maxCodes() {
        return intValue(KEY_MAX_CODES, DEFAULT_MAX_CODES);
    }

    private String text(String key) {
        try {
            String value = reader.getString(key);
            if (value == null) {
                return null;
            }
            value = value.trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private int intValue(String key, int fallback) {
        String value = text(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 相对路径按程序文件夹展开，并在「工作目录 → jar 目录 → jar 上级 → jar 内自带」里找
     * （见 {@link updater.cfg.ScriptPaths}）；jar 被挪到别处时照样能跑
     */
    public static File resolve(String path) {
        return updater.cfg.ScriptPaths.resolve(path);
    }
}
