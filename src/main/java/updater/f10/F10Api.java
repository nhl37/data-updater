package updater.f10;

import updater.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * F10 用到的几个公开数据接口的 GET 封装。
 *
 * <p>三家域名（东财数据中心 / 东财 F10 / 东财公告）都要求带浏览器 UA，其中数据中心还要
 * {@code Referer: https://data.eastmoney.com/}，否则会被拒。这里统一处理，超时 8 秒——
 * 这几个接口都在国内，正常 200~500ms，8 秒足够；超时就让上层显示「加载失败」，
 * 不能因为一个接口挂住整个 F10 画面。</p>
 */
public final class F10Api {

    /** 连接与读取超时（毫秒） */
    private static final int TIMEOUT = 8000;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private F10Api() {
    }

    public static String get(String url) throws Exception {
        return get(url, "https://data.eastmoney.com/");
    }

    public static String get(String url, String referer) throws Exception {
        return get(url, referer, TIMEOUT);
    }

    /**
     * 指定超时的版本。
     *
     * <p>默认 8 秒是给「点开某只股票」这类单次请求定的；批量并发获取（股东季度同步一次派 8 个线程）
     * 时服务端排队会明显变慢，8 秒不够用，调用方需要自己放长一点，否则会成片「Read timed out」。</p>
     */
    public static String get(String url, String referer, int timeoutMillis) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Accept", "*/*");
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + responseCode);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String text = buffer.toString(StandardCharsets.UTF_8);
                if (Log.outDebugLog) {
                    Log.out("F10 请求 " + url + " → " + text.length() + " 字节");
                }
                return text;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 6 位代码 → 东财 F10 接口要的「市场+代码」写法：600000 → SH600000
     */
    public static String marketCode(String code) {
        String plain = plain(code);
        if (plain.isEmpty()) {
            return "";
        }
        if (plain.startsWith("6")) {
            return "SH" + plain;
        }
        if (plain.startsWith("9")) {
            return "SH" + plain;
        }
        // 沪市场内基金（ETF / LOF）也是 SH；深市基金 15/16 落到下面的默认 SZ
        if (plain.startsWith("5")) {
            return "SH" + plain;
        }
        if (plain.startsWith("4") || plain.startsWith("8")) {
            return "BJ" + plain;
        }
        return "SZ" + plain;
    }

    /**
     * 6 位代码 → 数据中心要的 secucode：600000 → 600000.SH
     */
    public static String secuCode(String code) {
        String market = marketCode(code);
        if (market.isEmpty()) {
            return "";
        }
        return market.substring(2) + "." + market.substring(0, 2);
    }

    /**
     * 去掉 sh/sz/bj 前缀与 .SH 后缀，得到 6 位代码
     */
    public static String plain(String code) {
        if (code == null) {
            return "";
        }
        String text = code.trim().toLowerCase();
        if (text.startsWith("sh") || text.startsWith("sz") || text.startsWith("bj")) {
            text = text.substring(2);
        }
        int dot = text.indexOf('.');
        if (dot > 0) {
            text = text.substring(0, dot);
        }
        return text.trim();
    }
}
