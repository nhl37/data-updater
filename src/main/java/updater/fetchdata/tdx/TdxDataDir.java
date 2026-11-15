package updater.fetchdata.tdx;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;

import java.io.File;

/**
 * 本地数据来源目录的统一读取与校验。
 *
 * <p>所有读本地数据的任务都走这里，目录没配或不存在时给出同一条可照做的提示，
 * 而不是各自抛「please config tdx_path」或者空指针。</p>
 *
 * <p><b>调用方和更新器各配各的</b>：调用方读 {@code tdx.path}；更新器启动时调用
 * {@link #useUpdaterScope()} 改读 {@code updater.tdx.path}，默认空 —— 更新器单独分发，
 * 不该继承调用方的本地数据来源配置，用户得在更新器里重新选一次。</p>
 */
public final class TdxDataDir {

    /** 更新器自己的本地数据来源目录配置项（默认空） */
    public static final String KEY_UPDATER = "updater.tdx.path";

    /** 目录不对时统一给用户的处理办法：更新程序右上就有这个入口 */
    public static final String CHECK_HINT =
            "请点击数据更新程序右上「路径设置」，在「本地数据来源」里重新选择目录。";

    /** 当前进程读哪个配置项：调用方 tdx.path / 更新器 updater.tdx.path */
    private static String configKey = AppConfigManager.KEY_TdxPath;

    private TdxDataDir() {
    }

    /** 切到更新器作用域：此后读 {@value #KEY_UPDATER}，默认空 */
    public static void useUpdaterScope() {
        configKey = KEY_UPDATER;
    }

    /** 当前进程使用的配置项名（路径设置界面写回时用） */
    public static String configKey() {
        return configKey;
    }

    /** 配置里的本地数据来源目录，未配置时返回空字符串 */
    public static String configuredPath() {
        try {
            String path = AppConfigManager.getInstance().getConfigReader()
                    .getString(configKey);
            String text = path == null ? "" : path.trim();
            // 出厂包曾经把开发机的 POSIX 路径（/home/xxx/...）写进 updater.tdx.path，
            // 在 Windows 上永远不存在。当成「没配置」处理，除权任务才会给出去
            // 路径设置里重选的通���，而不是跑到别人的目录里找 gbbq。
            return isForeignPath(text) ? "" : text;
        } catch (ConfigException e) {
            return "";
        }
    }

    /** 这个路径是不是「另一个操作系统上的绝对路径」 */
    private static boolean isForeignPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String text = path.replace('\\', '/');
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        if (windows) {
            return text.startsWith("/home/") || text.startsWith("/Users/");
        }
        return text.matches("^[A-Za-z]:/.*");
    }

    /** 本地数据来源目录，必须存在 */
    public static File require() throws ConfigException {
        String path = configuredPath();
        if (path.isEmpty()) {
            throw new ConfigException("没有配置本地数据来源目录。\n" + CHECK_HINT);
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new ConfigException("本地数据来源目录不存在：" + dir.getAbsolutePath() + "\n" + CHECK_HINT);
        }
        return dir;
    }

    /** 本地数据来源目录（安装目录下的 vipdoc），必须存在 */
    public static File requireVipdoc() throws ConfigException {
        File vipdoc = new File(require(), "vipdoc");
        if (!vipdoc.isDirectory()) {
            throw new ConfigException("本地数据来源目录里找不到 vipdoc 子目录：" + vipdoc.getAbsolutePath()
                    + "\n" + CHECK_HINT);
        }
        return vipdoc;
    }
}
