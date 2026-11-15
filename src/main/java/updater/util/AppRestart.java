package updater.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 原地重启程序：用当前 JVM 的参数重新拉起一个进程，然后结束自己。
 *
 * <p>为什么需要它：挂载数据、改配置、换模型这些操作写的是文件，必须重开程序才生效。
 * 以前提示只有一句「请重启程序」，用户得自己关窗口、再找图标双击 —— 桌面被一堆窗口盖住时
 * 这一步经常被漏掉，于是「改了没生效」变成了 bug 报告。</p>
 *
 * <p>怎么拉起新进程：不靠反射找主类（发布包被 ProGuard 混淆过，类名可能已改），
 * 而是读运行时属性 {@code sun.java.command} —— 它就是启动时真正用的那个入口
 * （{@code java -jar xx.jar} 时是 jar 路径，{@code -cp ... 主类} 时是类名）。
 * 类路径直接用当前 {@code java.class.path}，堆上限沿用当前 {@code maxMemory}，
 * 工作目录沿用 {@code user.dir}（程序所有相对路径都靠它）。</p>
 *
 * <p>先后顺序：新进程立刻启动，但会被要求先睡一会儿（{@link #DELAY_PROP}），
 * 老的先退干净，避免两个进程同时抢挂载库/配置文件。</p>
 */
public final class AppRestart {

    /** 新进程启动后先等待的毫秒数：留给老进程退干净（关库、写偏好、释放文件锁） */
    private static final String DELAY_PROP = "updater.restart.delay";

    /** 等待上限：属性被手改成离谱的值时也不至于卡住启动 */
    private static final long MAX_DELAY_MS = 8000L;

    private AppRestart() {
    }

    /**
     * 如果这次是被「重启」拉起来的，先等老进程退干净再往下走。
     *
     * <p>放在 {@code main} 最前面调用：越早睡越安全，此时还没有打开任何库或窗口。</p>
     */
    public static void awaitPreviousExit() {
        String raw = System.getProperty(DELAY_PROP);
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        System.clearProperty(DELAY_PROP); // 只生效一次，避免后面重启又叠加等待
        long delay;
        try {
            delay = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            delay = 1500L;
        }
        delay = Math.max(0L, Math.min(MAX_DELAY_MS, delay));
        if (delay == 0L) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 拉起一个新进程，成功返回 true（此时调用方应尽快结束自己）。
     *
     * <p>启动失败不抛异常：返回 false 让调用方回退成「手动重启」的提示，
     * 总比弹一个栈信息好。</p>
     */
    public static boolean relaunch() {
        List<String> command = buildCommand();
        if (command.isEmpty()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            String workDir = updater.cfg.ProgramHome.path();
            pb.directory(new File(workDir));
            // 输出丢掉：新进程接着往老控制台写会串台，也没有人看
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return true;
        } catch (Exception e) {
            System.err.println("重启失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 重启：拉起新进程并退出当前 JVM。
     *
     * @return 拉起成功返回 true（当前进程已经在退出路上）；失败返回 false，调用方负责提示
     */
    public static boolean restart() {
        if (!relaunch()) {
            return false;
        }
        // 走正常退出：数据库线程池有 shutdown hook，关库、落盘都靠它
        System.exit(0);
        return true;
    }

    /**
     * 拼启动命令。识别不了入口时返回空列表（宁可让用户手动重启，也不要拉一个空进程）。
     */
    private static List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        String java = javaExecutable();
        if (java == null) {
            return cmd;
        }
        cmd.add(java);
        cmd.add("-Dfile.encoding=UTF-8");
        // 工作目录：程序里 30+ 处 user.dir 相对路径都指向它，必须原样带过去
        cmd.add("-Dupdater.home=" + updater.cfg.ProgramHome.path());
        cmd.add("-D" + DELAY_PROP + "=1500");
        long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (maxMb > 0) {
            cmd.add("-Xmx" + maxMb + "m");
        }

        String launched = System.getProperty("sun.java.command", "").trim();
        String entry = firstToken(launched);
        String classPath = System.getProperty("java.class.path", "");
        if (entry.isEmpty()) {
            return clearAndEmpty(cmd);
        }
        if (entry.toLowerCase().endsWith(".jar")) {
            // java -jar 启动：沿用它，别改写成 -cp（jar 里可能另有 Class-Path 依赖）
            cmd.add("-jar");
            cmd.add(entry);
            cmd.addAll(restTokens(launched));
            return cmd;
        }
        if (classPath == null || classPath.trim().isEmpty()) {
            return clearAndEmpty(cmd);
        }
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add(entry);
        cmd.addAll(restTokens(launched));
        return cmd;
    }

    private static List<String> clearAndEmpty(List<String> cmd) {
        cmd.clear();
        return cmd;
    }

    /** 当前 JVM 自己的 java 可执行文件（重启必须用同一个运行时，别去猜 PATH 里的 java） */
    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File exe = new File(home, "bin" + File.separator + (windows ? "java.exe" : "java"));
        return exe.isFile() ? exe.getAbsolutePath() : null;
    }

    private static String firstToken(String text) {
        List<String> tokens = restTokens(text);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    /** 按空白切分，双引号里的内容算一段（路径带空格的情况） */
    private static List<String> restTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
