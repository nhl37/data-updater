package updater.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机现在的外网 IP：挨个问几个公开的回显服务，谁答得出来就用谁。
 *
 * <p><b>为什么非得问外面</b>：本机网卡上看到的地址在路由器后面，重拨换了公网地址，
 * 本机那个地址却一点不变 —— 拿它当外网 IP 会给人「没换」的假结论。只有对面的服务
 * 看得见真正走出去的地址。</p>
 *
 * <p>只认 IPv4，取数的线路全走 IPv4。一家都没答出来就沿用上一次的结果，
 * 界面宁可继续显示旧地址，也不编一个出来。</p>
 */
public final class PublicIp {

    /** 先后顺序：先国内两家（实测几百毫秒答出来），不应声再挨个试备用那几个 */
    private static final String[] ENDPOINTS = {
            "https://myip.ipip.net",
            "https://ddns.oray.com/checkip",
            "https://4.ipw.cn",
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
    };

    private static final Pattern IPV4 =
            Pattern.compile("(?<!\\d)(\\d{1,3}(?:\\.\\d{1,3}){3})(?!\\d)");

    /** 单家的超时：五家全不应声也就十几秒，而且整个都发生在后台线程 */
    private static final int TIMEOUT_MILLIS = 3000;

    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private static volatile String last = "";

    private PublicIp() {
    }

    /** 最近一次问到的地址，从来没问到过返回空串；不发请求 */
    public static String current() {
        return last;
    }

    /**
     * 后台问一次，问完把结果交给 {@code onDone}（在后台线程回调，界面自己切回 EDT）。
     *
     * <p>上一次还没回来就跳过这一轮，不排队 —— 定时刷新的话下一轮自然会再问。</p>
     */
    public static void refreshAsync(final Consumer<String> onDone) {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (String endpoint : ENDPOINTS) {
                        String ip = ask(endpoint);
                        if (!ip.isEmpty()) {
                            last = ip;
                            break;
                        }
                    }
                } finally {
                    inFlight.set(false);
                }
                onDone.accept(last);
            }
        }, "public-ip");
        thread.setDaemon(true);
        thread.start();
    }

    /** @return 应答里的 IPv4；这家没答出来返回空串 */
    private static String ask(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MILLIS);
            conn.setReadTimeout(TIMEOUT_MILLIS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "";
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && body.length() < 400) {
                    body.append(line).append(' ');
                }
                String found = firstIpv4(body.toString());
                return found == null ? "" : found;
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            // 这家连不上 / 答得不像话，换下一家
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String firstIpv4(String body) {
        Matcher m = IPV4.matcher(body);
        while (m.find()) {
            if (usable(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    /** 像样才会是路由器拨出来的那个地址：各段不超 255、不带前导 0、不是本机与组播段 */
    private static boolean usable(String ip) {
        String[] parts = ip.split("\\.");
        int first = -1;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return false;
            }
            if (i == 0) {
                first = value;
            }
        }
        return first > 0 && first != 127 && first < 224;
    }
}
