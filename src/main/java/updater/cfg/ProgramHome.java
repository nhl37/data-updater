package updater.cfg;

import java.io.File;

/**
 * 程序文件夹 —— {@code config.properties}、{@code data/}、{@code logs/} 这些相对路径的落点。
 *
 * <p><b>为什么不能只靠 {@code System.getProperty("user.dir")}</b>：JVM 的默认文件系统在启动时
 * 就把「当前目录」缓存住了，运行期改 {@code user.dir} 属性只对「直接读这个属性」的代码有用，
 * {@code new File("config.properties")} 这种相对路径仍然按启动目录展开。所以 jar 被放到程序目录
 * 以外（桌面、{@code D:/tools}）时，配置文件根本没被读到，除权任务会以
 * 「没有配置本地数据来源目录」失败 —— 2026-11-13 就踩了这个坑。</p>
 *
 * <p>现在程序文件夹由 {@code UpdaterMain} 在启动时定好（{@code --home} → 当前目录 →
 * jar 目录 → jar 上级 → 上次记住的位置），相对路径统一在这里拼成绝对路径。</p>
 */
public final class ProgramHome {

    /** 当前程序文件夹（启动时定，之后不再变） */
    private static volatile File dir =
            new File(System.getProperty("user.dir", ".")).getAbsoluteFile();

    private ProgramHome() {
    }

    /** 程序文件夹 */
    public static File dir() {
        return dir;
    }

    /** 程序文件夹的绝对路径（给 {@code ProcessBuilder.directory} 之类的 API 用） */
    public static String path() {
        return dir.getAbsolutePath();
    }

    /**
     * 设置程序文件夹。
     *
     * <p>系统属性一起改：还有些调用方直接读 {@code user.dir}（例如
     * {@code Paths.get(System.getProperty("user.dir"))}），让两处口径一致。</p>
     */
    public static void set(File home) {
        if (home == null) {
            return;
        }
        File absolute = home.getAbsoluteFile();
        dir = absolute;
        System.setProperty("user.dir", absolute.getPath());
    }

    /** 相对路径按程序文件夹展开；绝对路径原样返回 */
    public static File resolve(String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(dir, path);
    }
}
