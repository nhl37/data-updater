package updater.main.updater;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.ConfigFileEditor;

import java.io.File;
import java.io.IOException;

/**
 * 数据备份文件夹（config.properties 的 data.path.dbbak）的读取、保存与校验。
 *
 * <p>更新画面上的「数据备份文件夹」和盘后下载里的备份都走这里，保证两处看到的是同一个目录。</p>
 *
 * <p>外部程序启动时会通过 {@code --backup-dir} 把「数据更新」画面里配的备份目录传进来，
 * 这时以参数为准；没传参数（直接双击调试）才读 config.properties。
 * 用户在更新器里点「设置」选了新目录，会同时写回 config.properties 并覆盖参数值，
 * 下次调用方启动时传下来的就是新目录。</p>
 */
public final class BackupDir {

    public static final String KEY = AppConfigManager.KEY_DBBAKPath;

    /** 没配置时统一的提示：画面上就有设置入口 */
    public static final String NOT_CONFIGURED =
            "还没有指定数据备份文件夹。\n\n"
                    + "请点击更新画面右上「路径设置」，在「数据备份文件夹」里选择目录，"
                    + "或在 config.properties 中配置 data.path.dbbak。";

    /** 启动参数传进来的备份目录（空串 = 没传） */
    private static String argDir = "";

    private BackupDir() {
    }

    /** 记录启动参数里的备份目录（空串 / null = 没传） */
    public static void setFromArgs(String raw) {
        argDir = normalize(raw);
    }

    /** 配置的备份文件夹，未配置时返回空串 */
    public static String configured() {
        if (!argDir.isEmpty()) {
            return argDir;
        }
        try {
            return normalize(AppConfigManager.getInstance().getConfigReader().getString(KEY, ""));
        } catch (Exception e) {
            return "";
        }
    }

    /** 备份文件夹，未配置时抛出带操作指引的异常 */
    public static File require() throws ConfigException {
        String path = configured();
        if (path.isEmpty()) {
            throw new ConfigException(NOT_CONFIGURED);
        }
        return new File(path);
    }

    /** 写回 config.properties（目录不存在就先建出来），并让本进程立即生效 */
    public static File save(String raw) throws Exception {
        String path = normalize(raw);
        if (path.isEmpty()) {
            throw new IOException("数据备份文件夹不能为空");
        }
        File dir = new File(path);
        if (dir.exists() && !dir.isDirectory()) {
            throw new IOException("这个路径是文件，不是文件夹: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建备份文件夹: " + dir.getAbsolutePath()
                    + "\n请检查路径是否正确、是否有写权限。");
        }

        ConfigFileEditor editor = new ConfigFileEditor(
                new File(UpdaterMain.currentHome(), "config.properties"));
        editor.load();
        editor.addEntry(KEY, path);
        editor.save();
        AppConfigManager.getInstance().getConfigReader().reload();
        // 参数值也要跟着换：本进程后续读取以参数为准，不能让旧参数把新目录盖回去
        argDir = path;
        return dir;
    }

    /** 去掉引号、统一 / 分隔、去尾部斜杠（配置里写 Windows 路径也不出错） */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        value = value.replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
