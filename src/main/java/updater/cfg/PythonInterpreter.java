package updater.cfg;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * python 解释器的解析与自动探测。
 *
 * <p>除权、财报、股本三个任务都要调外部的 python 脚本（要 pytdx / pandas / akshare），
 * 到底用哪个解释器取决于用户机器上 python 装在哪。原来配置项默认写死 {@code python3}，
 * 而 Windows 上通常只有 {@code python.exe}、没有 {@code python3.exe}，用户点「除权」
 * 只会收到一句「python 脚本执行失败, exitCode=1」，根本看不出是解释器名字不对。</p>
 *
 * <p>现在：配置值优先（用户可以在「设置 → python 解释器」里指定），配置没写或写的
 * 那个跑不起来，就依次试 {@code python3} → {@code python} → {@code py}，取第一个能跑的。
 * 三个都没有才报错，并且报的是「没找到 python」而不是一串 exitCode。</p>
 */
public final class PythonInterpreter {

    /** 配置键：与 {@code TdxLocalXdxrReader.KEY_PYTHON_EXE} 同一项 */
    public static final String KEY = "tdx.dividend.pythonExe";

    /** 探测顺序：Linux/macOS 一般 python3；Windows 只有 python / py */
    private static final String[] CANDIDATES = {"python3", "python", "py"};

    /** 探测超时：--version 正常几十毫秒，卡住说明这个可执行有问题 */
    private static final int PROBE_TIMEOUT_SECONDS = 8;

    private static String cachedResolved;
    private static boolean resolved;

    private PythonInterpreter() {
    }

    /** 配置里写的值，没配返回空串 */
    public static String configured() {
        try {
            String value = AppConfigManager.getInstance().getConfigReader().getString(KEY, "");
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前生效的解释器。
     *
     * @return 可执行的命令名或绝对路径；一个都探测不到返回 {@code null}
     */
    public static synchronized String resolve() {
        if (resolved) {
            return cachedResolved;
        }
        resolved = true;
        String configured = configured();
        if (!configured.isEmpty()) {
            if (probe(configured)) {
                cachedResolved = configured;
                return cachedResolved;
            }
            System.out.println("配置的 python 解释器跑不起来: " + configured
                    + "，改用自动检测（设置 → python 解释器 里可以改）");
        }
        cachedResolved = detect();
        if (cachedResolved != null) {
            System.out.println("python 解释器: " + cachedResolved);
        } else {
            System.out.println("没有找到可用的 python 解释器（试过 "
                    + Arrays.toString(CANDIDATES) + "），"
                    + "依赖 python 的任务（除权 / 财报 / 股本）无法执行；"
                    + "可在「设置 → python 解释器」里手工指定路径");
        }
        return cachedResolved;
    }

    /** 依次探测候选，返回第一个能跑的；都没有返回 null */
    public static String detect() {
        for (String candidate : CANDIDATES) {
            if (probe(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 这个解释器能不能跑：{@code <exe> --version} 退出码为 0 才算。
     *
     * <p>带路径的值必须先是个真实文件 —— {@code ProcessBuilder} 对不存在的绝对路径
     * 会抛 IOException，直接在这里判掉，省得调用方去 catch。</p>
     */
    public static boolean probe(String exe) {
        if (exe == null || exe.trim().isEmpty()) {
            return false;
        }
        List<String> command = split(exe);
        if (command.isEmpty()) {
            return false;
        }
        String first = command.get(0);
        if ((first.contains("/") || first.contains("\\")) && !new File(first).isFile()) {
            return false;
        }
        command.add("--version");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            // 输出不读：--version 最多一行，不会把管道写满
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    /** 设置画面保存后清缓存，让下一次任务用新值 */
    public static synchronized void invalidate() {
        resolved = false;
        cachedResolved = null;
    }

    /** 按空格拆命令：解释器路径带空格时也能处理（{@code "C:\Program Files\...\python.exe"} 要带引号） */
    public static List<String> split(String exe) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < exe.length(); i++) {
            char c = exe.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ' ' && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}
