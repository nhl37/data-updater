package updater.log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 极简文件日志。
 *
 * <p><b>为什么改成一直开着文件</b>：原来每写一行都要 {@code FileWriter} 打开 + 关闭一次。
 * 全市场更新一分钟能写几百行，Windows 上每次 open/close 都可能被实时防护扫一遍；
 * 一旦某个文件被占住，写日志的线程（心跳、任务线程）就停在那儿，
 * 表现出来就是「日志不打了，db 也不再生成了」。</p>
 *
 * <p><b>写不出去时降级丢弃</b>：日志永远不该拖住业务。失败后 30 秒内直接丢掉，
 * 过一会儿再重试，不让每一行都去撞一次坏盘 / 被占用的文件。</p>
 */
public class SimpleFileLogger {
    /** 单个日志文件上限，超了就转成 .1（只留一份旧的） */
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    /** 写失败后多久再试一次 */
    private static final long RETRY_MILLIS = 30_000L;
    /** INFO 日志最多攒这么多行就 flush 一次 */
    private static final int FLUSH_LINES = 64;
    /** INFO 日志最多攒这么久就 flush 一次 */
    private static final long FLUSH_MILLIS = 500L;
    /** 是否每行都 flush（默认关，见下面的说明） */
    private static volatile Boolean flushEachLineCache;

    private final String logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private Writer writer;
    private long writtenBytes;
    private long retryAtMillis;
    private boolean reportedFailure;
    private int bufferedLines;
    private long lastFlushMillis;

    public SimpleFileLogger(String logFilePath) {
        // 相对路径挂程序文件夹：jar 被放到别处启动时日志不要跟着落到启动目录去
        File resolved = updater.cfg.ProgramHome.resolve(logFilePath);
        this.logFile = resolved.getPath();
        // 确保日志目录存在
        File file = resolved;
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.isDirectory()) {
            parentDir.mkdirs();
        }
        // 攒批之后，进程正常退出时必须把最后不满一个缓冲的内容刷出去，否则最后几行会丢
        try {
            Runtime.getRuntime().addShutdownHook(new Thread("log-flush") {
                @Override
                public void run() {
                    flushAndClose();
                }
            });
        } catch (Throwable ignored) {
            // 注册不了就算了（比如已经进入关机流程），最多丢最后几行日志
        }
    }

    /**
     * 是否每行都 flush。
     *
     * <p><b>默认关</b>：{@code Writer.flush()} 只是把 {@code OutputStreamWriter} 的 8KB 编码缓冲
     * 交给内核，并不等于 fsync，所以「每行 flush」换不来更高的可靠性，只换来每行一次系统调用。
     * 原来每行开/关一次文件（4 次系统调用）的写法更慢；Windows 上每次 open/close 还会被
     * 实时防护扫一遍。真需要逐行落盘时在 config.properties 里打开
     * {@code log.flush.eachline=true}。</p>
     */
    private static boolean flushEachLine() {
        Boolean cached = flushEachLineCache;
        if (cached == null) {
            boolean value = false;
            try {
                value = updater.cfg.AppConfigManager.getInstance().getConfigReader()
                        .getBoolean("log.flush.eachline", false);
            } catch (Throwable ignored) {
                // 读不到配置就按默认不逐行 flush
            }
            cached = Boolean.valueOf(value);
            flushEachLineCache = cached;
        }
        return cached.booleanValue();
    }

    public synchronized void log(LogLevel level, String message) {
        String line = "[" + dateFormat.format(new Date()) + "] [" + level + "] " + message
                + System.lineSeparator();
        if (writer == null) {
            if (System.currentTimeMillis() < retryAtMillis) {
                // 刚失败过：这段时间直接丢，别让业务线程陪着重试
                return;
            }
            if (!open()) {
                return;
            }
        }
        try {
            if (writtenBytes > MAX_BYTES) {
                rotate();
            }
            writer.write(line);
            writtenBytes += line.length();
            bufferedLines++;
            reportedFailure = false;
            // 告警和错误必须立刻可见；INFO 攒批，避免每行一次系统调用
            boolean urgent = level != LogLevel.INFO;
            long now = System.currentTimeMillis();
            if (urgent || flushEachLine() || bufferedLines >= FLUSH_LINES
                    || now - lastFlushMillis >= FLUSH_MILLIS) {
                writer.flush();
                bufferedLines = 0;
                lastFlushMillis = now;
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    /** 把缓冲区刷出去（退出时用） */
    public synchronized void flushAndClose() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // 关不掉就算了
            }
            closeQuietly();
        }
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public void error(String message, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        log(LogLevel.ERROR, message + "\n" + sw);
    }

    private boolean open() {
        try {
            File file = new File(logFile);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            writtenBytes = file.length();
            lastFlushMillis = System.currentTimeMillis();
            return true;
        } catch (IOException e) {
            fail(e);
            return false;
        }
    }

    /** 日志文件太大：关掉、改成 .1、重新开一个 */
    private void rotate() throws IOException {
        closeQuietly();
        File file = new File(logFile);
        File old = new File(logFile + ".1");
        if (old.exists() && !old.delete()) {
            // 旧的删不掉（被打开着）就直接清空当前文件，别为了轮转把写入卡死
            if (!file.delete() || !file.createNewFile()) {
                throw new IOException("日志轮转失败: " + file.getAbsolutePath());
            }
            writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            writtenBytes = 0;
            return;
        }
        if (!file.renameTo(old) && !file.delete()) {
            throw new IOException("日志轮转失败: " + file.getAbsolutePath());
        }
        writtenBytes = 0;
        if (!open()) {
            throw new IOException("日志轮转后重新打开失败: " + file.getAbsolutePath());
        }
    }

    private void fail(IOException e) {
        closeQuietly();
        retryAtMillis = System.currentTimeMillis() + RETRY_MILLIS;
        if (!reportedFailure) {
            reportedFailure = true;
            System.err.println("写入日志失败（" + logFile + "），" + (RETRY_MILLIS / 1000)
                    + " 秒内不再重试: " + e.getMessage());
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 关不掉就算了，下次重开会覆盖
            }
            writer = null;
        }
    }

    public enum LogLevel {
        INFO, WARN, ERROR
    }

    // 使用示例
    public static void main(String[] args) {
        SimpleFileLogger logger = new SimpleFileLogger("logs/custom.log");

        logger.info("应用程序启动");
        logger.warn("磁盘空间不足");
        logger.error("数据库连接失败", new RuntimeException("连接超时"));
    }
}
