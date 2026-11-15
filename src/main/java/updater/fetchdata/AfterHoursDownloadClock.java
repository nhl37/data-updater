package updater.fetchdata;

import updater.log.Log;
import updater.mount.MountConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 「今天有没有跑过盘后数据下载」的记录。
 *
 * <p>不能用 {@link PriceUpdateClock} 当这个标记：补最新价格、从本地 CSV 加载行情
 * 也都会写 {@code price_update.txt}，分不清「今天只是盘中抓了个价」和「今天做过完整的
 * 盘后下载」。首页检索要拦的正是后者 —— 前者只写当天这一根日K，后者才会把除权、历史、
 * 分钟线一起补齐，所以单独记一个日期文件，只有盘后下载链路写它。</p>
 *
 * <p>文件放在股票库目录的父目录（数据文件夹），与 {@code price_update.txt} 同一层：
 * 更新器的数据文件夹默认跟随调用方挂载，两边读到的就是同一个文件，重启后仍然有效。
 * 目录不存在时不补建 —— 挂载盘 / NAS 没挂上时静默写进别处只会更误导人。</p>
 */
public final class AfterHoursDownloadClock {

    private static final String FILE_NAME = "after_hours_download.txt";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private AfterHoursDownloadClock() {
    }

    /** 一次盘后下载真的写入了数据之后调用：记下今天 */
    public static void markToday() {
        File file = file();
        if (file == null) {
            return;
        }
        try {
            Files.write(file.toPath(), LocalDate.now().format(DATE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 标记写不进去不该让整次下载失败：最坏结果是首页多提醒一句
            Log.error("保存盘后下载标记失败：" + e.getMessage());
        }
    }

    /** 最后记录日期 yyyyMMdd；没记录 / 文件损坏返回 0 */
    public static int lastDownloadedDate() {
        File file = file();
        if (file == null || !file.isFile()) {
            return 0;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return text.length() == 8 ? Integer.parseInt(text) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 今天是否已经跑过盘后下载 */
    public static boolean downloadedToday() {
        return lastDownloadedDate() == today();
    }

    /** 今天的 yyyyMMdd */
    public static int today() {
        return Integer.parseInt(LocalDate.now().format(DATE));
    }

    private static File file() {
        String dbDir = MountConfig.currentDbDir();
        if (dbDir == null || dbDir.trim().isEmpty()) {
            return null;
        }
        File parent = new File(dbDir).getParentFile();
        return parent == null ? null : new File(parent, FILE_NAME);
    }
}
