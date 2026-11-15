package updater.fetchdata;

import updater.db.FinanceDBManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 股票价格数据的最后更新时间。
 *
 * <p>盘中更新（主菜单第 2 项）写完当天那根未走完的日K之后，这里记下「数据是几点拿到的」。
 * 脚本里的 {@code AMO_TDX}/{@code HSL_TDX} 用它判断当天这根K线是不是只走了一半：
 * 用更新时间估算全天，而不是用「现在几点」——晚上复盘时现在早已收盘，
 * 拿现在的时间去算早上 10 点更新的数据，会把半天的量当成全天。</p>
 *
 * <p>时间存在 {@code data/price_update.txt}（一行毫秒数），跟着数据目录走，
 * 重启程序后仍然有效。只有成功写完价格数据才更新它。</p>
 */
public final class PriceUpdateClock {

    private static final String FILE_NAME = "price_update.txt";

    private static volatile long cachedMillis = -1;
    private static volatile boolean loaded;

    private PriceUpdateClock() {
    }

    /** 价格数据刚写完：记录当前时间 */
    public static void markUpdated() {
        long now = System.currentTimeMillis();
        write(now);
        cachedMillis = now;
        loaded = true;
    }

    /**
     * 最后更新时间（毫秒）；没有记录时返回 0。
     * <p>0 表示「不知道数据是什么时候更新的」，调用方应退回实际值，不要猜。
     */
    public static long lastUpdatedMillis() {
        if (!loaded) {
            load();
        }
        return cachedMillis;
    }

    /** 最后更新时间的日期 yyyyMMdd；没有记录返回 0 */
    public static int lastUpdatedDate() {
        long millis = lastUpdatedMillis();
        if (millis <= 0) {
            return 0;
        }
        LocalDateTime time = toLocal(millis);
        return time.getYear() * 10000 + time.getMonthValue() * 100 + time.getDayOfMonth();
    }

    /** 最后更新时间的时刻 HHmm（如 10:15 → 1015）；没有记录返回 0 */
    public static int lastUpdatedHhmm() {
        long millis = lastUpdatedMillis();
        if (millis <= 0) {
            return 0;
        }
        LocalDateTime time = toLocal(millis);
        return time.getHour() * 100 + time.getMinute();
    }

    private static LocalDateTime toLocal(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private static synchronized void load() {
        long value = 0;
        try {
            File file = file();
            if (file.isFile()) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    value = Long.parseLong(text);
                }
            }
        } catch (Exception e) {
            // 文件损坏或没有权限都当成「没有记录」，不影响脚本计算
            value = 0;
        }
        cachedMillis = value;
        loaded = true;
    }

    private static void write(long millis) {
        try {
            File file = file();
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(file.toPath(), String.valueOf(millis).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            updater.log.Log.error("保存价格数据更新时间失败：" + e.getMessage());
        }
    }

    private static File file() {
        File dir;
        try {
            dir = FinanceDBManager.resolveDbFile(null).getParentFile();
        } catch (Exception e) {
            dir = new File("data");
        }
        if (dir == null) {
            dir = new File("data");
        }
        return new File(dir, FILE_NAME);
    }
}
