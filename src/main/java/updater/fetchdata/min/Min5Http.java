package updater.fetchdata.min;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 在线 5 分钟线路共用的取数动作：一个 GET，带重试，异常翻成中文。
 *
 * <p>重试和「不复用连接」这两条不是随手加的：这三家都会在请求太密时直接掐断连接，
 * 表现是 {@code Unexpected end of file from server}。{@link HttpURLConnection} 默认把连接
 * 留在池子里复用，可对方早关了，下一次把请求发到这条死连接上就又是一次「假失败」，
 * 所以每次都显式 {@code Connection: close}。</p>
 */
final class Min5Http {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 一次请求最多试几次。2 次不够：对方主动断开往往连断两三次才恢复，
     * 退避之后再试基本都能拿到。
     */
    private static final int MAX_ATTEMPTS = 4;

    private Min5Http() {
    }

    /**
     * @param referer 有些线路不带 Referer 直接 403（新浪就是这样），没有就传 null
     * @throws IOException 重试用完仍然失败，message 已经是可以直接给用户看的中文
     */
    static String get(String url, String referer) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return getOnce(url, referer);
            } catch (BlockedException e) {
                // 403 / 429 是「在拒绝你」，不是「抖了一下」：再试 3 次也还是拒绝，直接往上抛，
                // 由上层决定换数据源还是换 IP
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        // 退避带抖动：多只并发时错开重来，否则被限流后一批线程会整齐地一起撞回去
                        Thread.sleep(300L * attempt + (long) (Math.random() * 250));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw new IOException(humanReadable(last), last);
    }

    private static String getOnce(String url, String referer) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setRequestProperty("Connection", "close");
            int status = conn.getResponseCode();
            if (status == 403 || status == 429) {
                throw new BlockedException("线路返回 HTTP " + status + "（本机被限流或拒绝）");
            }
            if (status != 200) {
                throw new IOException("线路返回 HTTP " + status);
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream buf = new ByteArrayOutputStream(16384)) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 把底层异常翻成一句用户能看懂的话：{@code Unexpected end of file from server}
     * 原样显示给用户等于没说，原始异常留在 cause 里，日志能看到。
     */
    private static String humanReadable(IOException e) {
        if (e == null) {
            return "行情接口没有响应，已自动重试 " + MAX_ATTEMPTS + " 次";
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.io.EOFException || msg.contains("Unexpected end of file")) {
            return "行情接口中途断开了连接（网络不稳或被限流），已自动重试 " + MAX_ATTEMPTS + " 次";
        }
        if (msg.toLowerCase(Locale.ROOT).contains("timed out")) {
            return "行情接口响应超时，已自动重试 " + MAX_ATTEMPTS + " 次";
        }
        if (msg.trim().isEmpty()) {
            return "行情接口没有响应，已自动重试 " + MAX_ATTEMPTS + " 次";
        }
        return msg;
    }
}
