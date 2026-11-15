package updater.cfg;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * python 脚本与中间文件的落点解析 —— 不再赌「启动时的当前目录」。
 *
 * <p><b>为什么需要它</b>：更新器里所有相对路径都挂在 {@code user.dir}（程序文件夹）上，
 * 而 {@code user.dir} 只有「当前目录 / jar 目录 / jar 上级」里能找到 {@code config.properties}
 * 时才等于程序文件夹。把 jar 单独挪到别处（桌面、{@code D:/tools}）启动时，
 * {@code user.dir} 就变成启动目录，{@code python/read_tdx_gbbq.py} 自然找不到 ——
 * 除权任务的表象就是「python 脚本执行失败 / 脚本不存在」，财报、股本也一个道理。</p>
 *
 * <p><b>脚本查找顺序</b>（配置项写相对路径时）：程序文件夹 → jar 目录 → jar 上级目录 →
 * jar 内 {@code /python/<文件名>}（随包内置，释放到系统临时目录后用）。
 * 配置成绝对路径时原样返回，不做任何改写。</p>
 *
 * <p><b>中间文件</b>（除权 csv、代码清单）：相对路径挂程序文件夹；程序文件夹不可写时
 * 退回临时目录 —— 这些只是过程中的导出文件，落点不影响最终结果。</p>
 */
public final class ScriptPaths {

    /** jar 内脚本资源的目录前缀 */
    private static final String BUNDLED_PREFIX = "/python/";

    /** 释放内置脚本的缓存子目录（相对系统临时目录） */
    private static final String CACHE_SUBDIR = "datatools-updater/python";

    private ScriptPaths() {
    }

    /**
     * 解析脚本路径。
     *
     * @param configured 配置里的值（可能是相对路径 {@code python/read_tdx_gbbq.py}、也可能是绝对路径）
     * @return 解析出的文件；配置为空返回 {@code null}。磁盘与 jar 内都没有时返回按工作目录展开的路径，
     *         由调用方给出「脚本不存在」的明确报错
     */
    public static File resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        String text = configured.trim();
        File direct = new File(text);
        if (direct.isAbsolute()) {
            return direct;
        }
        for (File base : searchRoots()) {
            File candidate = new File(base, text);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File bundled = extractBundled(nameOf(text));
        if (bundled != null) {
            return bundled;
        }
        return direct.getAbsoluteFile();
    }

    /**
     * 中间文件的落点。相对路径按程序文件夹展开并建好父目录；程序文件夹不可写时
     * 退回临时目录（文件名保持不变）。
     */
    public static File workFile(String configured) {
        String text = configured == null ? "" : configured.trim();
        File file = new File(text);
        if (file.isAbsolute()) {
            ensureParent(file);
            return file;
        }
        File home = ProgramHome.dir();
        File candidate = new File(home, text);
        if (ensureParent(candidate)) {
            return candidate;
        }
        File fallback = new File(cacheDir(), file.getName());
        ensureParent(fallback);
        System.out.println("程序文件夹不可写，中间文件改放: " + fallback.getAbsolutePath());
        return fallback;
    }

    /**
     * 把 jar 内 {@code /python/<fileName>} 释放到临时目录并返回它。
     *
     * <p>内容一致就不重写（避免每次跑任务都动一次磁盘），不一致就覆盖 ——
     * 这样换了新版本的 jar、脚本有更新，下次运行自然用新的。</p>
     *
     * @return 释放后的文件；jar 里没有这个脚本或临时目录不可写时返回 {@code null}
     */
    public static File extractBundled(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        URL url = ScriptPaths.class.getResource(BUNDLED_PREFIX + fileName);
        if (url == null) {
            return null;
        }
        File dir = cacheDir();
        if (dir == null) {
            return null;
        }
        File target = new File(dir, fileName);
        try {
            byte[] bundled = readAll(url);
            if (target.isFile() && target.length() == bundled.length
                    && sameBytes(target, bundled)) {
                return target;
            }
            File tmp = new File(dir, fileName + ".tmp");
            Files.write(tmp.toPath(), bundled);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Windows 上目标正被别的进程读着时 move 会失败：直接用临时文件也能跑
                System.out.println("释放内置脚本时替换失败，改用临时副本: " + e.getMessage());
                return tmp;
            }
            System.out.println("已释放内置脚本: " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            System.out.println("释放内置脚本失败(" + fileName + "): " + e.getMessage());
            return null;
        }
    }

    /** 脚本按哪些目录找：程序文件夹 → jar 目录 → jar 上级目录 */
    private static List<File> searchRoots() {
        List<File> roots = new ArrayList<File>();
        roots.add(ProgramHome.dir());
        File jarDir = BundledDefaults.jarDir();
        if (jarDir != null) {
            roots.add(jarDir);
            if (jarDir.getParentFile() != null) {
                roots.add(jarDir.getParentFile());
            }
        }
        return roots;
    }

    /** 内置脚本的存放目录（系统临时目录下的固定子目录） */
    private static File cacheDir() {
        File dir = new File(System.getProperty("java.io.tmpdir", "."), CACHE_SUBDIR);
        if (dir.isDirectory() || dir.mkdirs()) {
            return dir;
        }
        return null;
    }

    /** 建父目录；返回父目录是否可写（父目录就是当前目录时也认） */
    private static boolean ensureParent(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) {
            return false;
        }
        if (!parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        return parent.canWrite();
    }

    private static String nameOf(String path) {
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static byte[] readAll(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean sameBytes(File file, byte[] expected) throws IOException {
        byte[] actual = Files.readAllBytes(file.toPath());
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
