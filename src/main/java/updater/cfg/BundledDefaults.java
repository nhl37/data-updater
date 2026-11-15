package updater.cfg;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 随包常量配置的读取入口：<b>磁盘优先，jar 内兜底</b>。
 *
 * <p>有些配置项是纯常量（东财接口地址、行情源名字），用户既不会填、也不该填 ——
 * 而发布包从 2026-11-11 起整个 {@code config/} 都不随包，于是用户机器上
 * 「除权」任务必然 {@code FileNotFoundException}。</p>
 *
 *
 * <p>只读、带缓存：这四个来源在一次运行里不会变，重复 load 没意义。</p>
 */
public final class BundledDefaults {

    private static final Map<String, Properties> CACHE = new HashMap<String, Properties>();

    private BundledDefaults() {
    }

    /**
     * 读一份配置。
     *
     * @param relativePath 形如 {@code config/sina.properties}
     * @return 一定不为 null；四个来源都没有时返回空 Properties（调用方按默认值走）
     */
    public static synchronized Properties load(String relativePath) {
        Properties cached = CACHE.get(relativePath);
        if (cached != null) {
            return cached;
        }
        Properties props = new Properties();
        File file = locate(relativePath);
        if (file != null) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
                CACHE.put(relativePath, props);
                return props;
            } catch (IOException e) {
                System.out.println("读取 " + file + " 失败，改用内置默认值: " + e.getMessage());
            }
        }
        loadBundled(props, relativePath);
        CACHE.put(relativePath, props);
        return props;
    }

    /**
     * 取单个配置项，缺失返回 {@code defaultValue}。
     *
     * @param relativePath 形如 {@code config/sina.properties}
     */
    public static String get(String relativePath, String key, String defaultValue) {
        String value = load(relativePath).getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    /** 存档位置：工作目录 → jar 目录 → jar 上级目录；都没有返回 null */
    private static File locate(String relativePath) {
        List<File> candidates = new ArrayList<File>();
        candidates.add(new File(relativePath));
        File jarDir = jarDir();
        if (jarDir != null) {
            candidates.add(new File(jarDir, relativePath));
            if (jarDir.getParentFile() != null) {
                candidates.add(new File(jarDir.getParentFile(), relativePath));
            }
        }
        for (File candidate : candidates) {
            if (candidate.canRead() && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** jar 内 {@code /defaults/<文件名>}，文件名取 relativePath 的最后一段 */
    private static void loadBundled(Properties props, String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String resource = "/defaults/" + name;
        URL url = BundledDefaults.class.getResource(resource);
        if (url == null) {
            System.out.println("没有内置 " + resource + "，使用代码里的默认值");
            return;
        }
        try (InputStream in = url.openStream()) {
            props.load(in);
        } catch (IOException e) {
            System.out.println("读取内置 " + resource + " 失败，使用代码里的默认值: " + e.getMessage());
        }
    }

    /** jar 所在目录；IDE 里直接跑（classes 目录）时返回 classes 目录 */
    public static File jarDir() {
        try {
            File self = new File(BundledDefaults.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return self.isDirectory() ? self : self.getParentFile();
        } catch (Exception e) {
            return null;
        }
    }
}
