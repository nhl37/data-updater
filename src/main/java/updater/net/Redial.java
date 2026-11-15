package updater.net;

import updater.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 「换 IP」：跑一条<b>用户自己填</b>的本机命令，让路由器 / 光猫重新拨号一次。
 *
 * <p><b>为什么不内置各品牌路由器的接口</b>：重拨的地址、登录方式、认证参数每家都不一样
 * （小米是 {@code /cgi-bin/luci/;stok=…/api/xqnetwork/pppoe_stop}，华硕是 {@code applyapp.cgi}，
 * OpenWrt 是 {@code ubus}），而且各代固件还在改。写死两三家的代价是：剩下的用户用不了，
 * 用得了的哪天固件一升级又坏了，还得跟着维护。这里只留一个「执行一行命令」的位置，
 * 用户按自己的型号填一行 curl 就通用，程序不碰任何账号密码。</p>
 *
 * <p><b>只在一种情况下会被调用</b>：一轮在线取数<b>一只都没成功</b>（见
 * {@code Min5Online}）—— 那才是「本机被这个站拒了」的信号。取到一部分就不动 IP，
 * 免得为了几十只股票把整台机器断网半分钟。</p>
 *
 * <p>没填命令时这里所有方法都是空操作，程序行为跟以前完全一样。</p>
 */
public final class Redial {

    /** config.properties 里的键：一行本机命令，默认空 = 不换 IP */
    public static final String KEY = "net.redial.command";

    /** 命令本身最多跑多久；卡住的命令不能把整趟补数拖死 */
    private static final int COMMAND_TIMEOUT_SECONDS = 90;

    /**
     * 重拨后等网络回来的上限。
     * 光猫重拨 + 拨号认证通常 20～40 秒；留到 2 分钟是给「顺手把路由器也重启了」那种写法。
     */
    private static final int WAIT_ONLINE_SECONDS = 120;

    /** 探测间隔：3 秒一次，够快也不至于把刚起来的链路又打一顿 */
    private static final int PROBE_INTERVAL_MILLIS = 3000;

    private static final int PROBE_TIMEOUT_MILLIS = 3000;

    /**
     * 探测用的目标：只要「链路通了」就算数，<b>不拿行情站的地址来探</b> ——
     * 被限速时那个地址照样连得上（拦在 HTTP 层），拿它探会得到「网络已恢复」的假结论。
     */
    private static final String PROBE_HOST = "www.baidu.com";
    private static final int PROBE_PORT = 443;

    private Redial() {
    }

    /** 用户填的重拨命令；没填返回空字符串 */
    public static String configured() {
        try {
            String value = updater.cfg.AppConfigManager.getInstance().getConfigReader().getString(KEY);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 有没有配重拨命令 —— 没配就别在日志里提「换 IP」，用户没要求这件事 */
    public static boolean available() {
        return !configured().isEmpty();
    }

    /**
     * 执行重拨并等网络回来。
     *
     * @param cancel 用户点了「停止」就别再等下去
     * @return 网络是否已经恢复（没配命令、命令跑不起来、等超时都算 false）
     */
    public static boolean redialAndWait(Consumer<String> log, AtomicBoolean cancel) {
        String command = configured();
        if (command.isEmpty()) {
            return false;
        }
        report(log, "正在重新拨号换 IP（按设置里填的命令）…");
        String output = execute(command, cancel);
        if (output == null) {
            report(log, "重拨命令没能跑起来，先按原线路再试一次；命令本身的问题见日志");
            return false;
        }
        if (!output.isEmpty()) {
            Log.info("重拨命令输出：" + output);
        }
        report(log, "重拨命令已执行，等网络回来（最多 " + WAIT_ONLINE_SECONDS + " 秒）…");
        return waitOnline(log, cancel);
    }

    /** 链路通不通（TCP 连一下，不发请求、不落任何数据） */
    public static boolean online() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关不上不影响结论
            }
        }
    }

    /** @return 命令输出（最多留前 20 行）；启动失败返回 null */
    private static String execute(String command, final AtomicBoolean cancel) {
        List<String> argv = new ArrayList<String>(3);
        if (isWindows()) {
            argv.add("cmd.exe");
            argv.add("/c");
        } else {
            argv.add("/bin/sh");
            argv.add("-c");
        }
        argv.add(command);

        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException e) {
            Log.error("重拨命令启动失败：" + e.getMessage());
            return null;
        }

        final StringBuilder collected = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                // 必须读走输出：命令输出多了会把管道塞满，process 就永远等不到结束
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    int lines = 0;
                    while ((line = reader.readLine()) != null) {
                        if (lines++ < 20 && collected.length() < 2000) {
                            collected.append(line.trim()).append(" | ");
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时读端会抛，忽略
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, "redial-output");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.error("重拨命令超过 " + COMMAND_TIMEOUT_SECONDS + " 秒没结束，已终止");
                process.destroyForcibly();
            } else if (process.exitValue() != 0) {
                Log.error("重拨命令返回 " + process.exitValue() + "（命令可以正常返回非 0，按已执行处理）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        if (cancel != null && cancel.get()) {
            process.destroyForcibly();
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return collected.toString().trim();
    }

    private static boolean waitOnline(Consumer<String> log, AtomicBoolean cancel) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WAIT_ONLINE_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (cancel != null && cancel.get()) {
                return false;
            }
            // 拨号过程中断网是正常的，先给几秒，别一上来就判「没回来」
            if (online()) {
                report(log, "网络已恢复，接着补");
                return true;
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        report(log, "等了 " + WAIT_ONLINE_SECONDS + " 秒网络还没回来，先按原线路再试一次");
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void report(Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
