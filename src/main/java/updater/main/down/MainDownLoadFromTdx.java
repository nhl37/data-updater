package updater.main.down;

import updater.cfg.ConfigException;
import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.log.Log;
import updater.main.updater.BackupDir;
import updater.mount.MountConfig;
import updater.fetchdata.ReadDataException;
import updater.util.HangWatchdog;
import updater.util.Parallelism;
import updater.util.TimeUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「更新数据-盘后数据下载 → 从本地数据来源加载」：把本地数据来源的 vipdoc 里最近 N 天的日线写进本地库。
 *
 * <p><b>为什么把「问天数 / 问备份」搬出任务线程</b>：更新画面的进度框本身是模态的
 * （{@code JDialog.setVisible(true)} 会开嵌套事件泵）。在任务线程里再弹
 * {@code JOptionPane} 属于「非 EDT 线程创建对话框」，Windows 上经常表现为对话框
 * 躲在进度框后面、或者干脆不响应 —— 用户看到的就是<b>日志停住、点也没反应</b>。
 * 现在弹框一律由画面在主线程问完，再把参数传进来（{@link #run(int, boolean, Consumer)}）。</p>
 */
public class MainDownLoadFromTdx {

    /** 一次盘后下载的结果 */
    public static class Result {
        /** 更新的天数 */
        public int days;
        /** 是否做了备份 */
        public boolean backedUp;
        /** 备份文件（没备份为空串） */
        public String backupFile = "";
        public long elapsedMillis;
    }

    /**
     * 备份当前数据库（整目录打 zip）。
     *
     * <p>文件多的时候要几十秒，所以先数一遍文件数、过程中每 500 个打一行 —— 原来只在
     * 全部完成时打一行，看起来像卡住。</p>
     */
    private static String backupDb(Consumer<String> out) throws ConfigException, IOException {
        String dbpath = MountConfig.currentDbDir();
        if (dbpath == null || dbpath.trim().isEmpty()) {
            out.accept("数据文件夹为空，跳过备份");
            return "";
        }
        File backupDir = BackupDir.require();
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            throw new IOException("备份文件夹建不出来: " + backupDir.getAbsolutePath()
                    + "\n请检查路径是否正确、是否有写权限。");
        }

        File dbDir = new File(dbpath);
        if (!dbDir.isDirectory()) {
            out.accept("数据库目录不存在，跳过备份");
            return "";
        }

        int fileCount = countFiles(dbDir);
        if (fileCount == 0) {
            out.accept("数据文件夹里还没有库文件，不需要备份");
            return "";
        }

        String timeStamp = TimeUtil.getCurrentDate();
        String zipFileName = new File(backupDir, "db_backup_" + timeStamp + ".zip").getAbsolutePath();
        out.accept("开始备份 " + fileCount + " 个文件到 " + zipFileName + "（文件多时需要一会儿）");

        try {
            zipDirectory(dbDir, zipFileName);
            out.accept("备份完成: " + zipFileName);
            Log.info("成功备份数据到：" + zipFileName);
            return zipFileName;
        } catch (IOException e) {
            throw new RuntimeException("备份数据失败：" + e.getMessage(), e);
        }
    }

    /**
     * 只做一次「整目录打包备份」，不碰数据。
     *
     * <p>给「东方财富」来源用：备份是数据安全上的兜底，不该因为换了下载来源就没了。</p>
     *
     * @return 备份文件路径；没有可备份的库时返回空串
     */
    public static String backupOnly(Consumer<String> out) throws Exception {
        return backupDb(out == null ? System.out::println : out);
    }

    /** 递归数文件个数（只用来给用户一个进度预期） */
    private static int countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += countFiles(child);
            } else {
                count++;
            }
        }
        return count;
    }

    private static void zipDirectory(File sourceDir, String zipFilePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFilePath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zipFile(sourceDir, sourceDir.getName(), zos, new int[]{0});
        }
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zos, int[] counter)
            throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }

        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zos.putNextEntry(new ZipEntry(fileName));
                zos.closeEntry();
            } else {
                zos.putNextEntry(new ZipEntry(fileName + "/"));
                zos.closeEntry();
            }

            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zos, counter);
                }
            }
            return;
        }

        // 每 200 个文件报一次进度：备份卡住时，看门狗抓的堆栈里能看出停在哪个文件
        counter[0]++;
        if (counter[0] % 200 == 0) {
            HangWatchdog.logLine("打包备份，第 " + counter[0] + " 个文件: " + fileName);
        }

        try (FileInputStream fis = new FileInputStream(fileToZip)) {
            ZipEntry zipEntry = new ZipEntry(fileName);
            zos.putNextEntry(zipEntry);

            byte[] bytes = new byte[1024];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zos.write(bytes, 0, length);
            }
        }
    }

    /**
     * 跑一次盘后下载（不弹任何框）。
     *
     * @param days   要更新的天数（1~1000）
     * @param backup true = 先整目录备份
     * @param log    进度输出，null 打到控制台
     */
    public static Result run(int days, boolean backup, Consumer<String> log)
            throws IOException, ReadDataException, ConfigException {
        return run(days, backup, log, null, null, null);
    }

    /**
     * 带逐只台账的版本（更新画面用）：每只股票处理完报一次，界面据此给每只留一行。
     *
     * @param items     逐只进度，null 表示不需要
     * @param cancel    停止标志，可为 null
     * @param onlyCodes 只处理这些代码（续跑 / 重试失败），null = 全市场
     */
    public static Result run(int days, boolean backup, Consumer<String> log,
                             updater.util.ItemProgress items,
                             java.util.concurrent.atomic.AtomicBoolean cancel,
                             java.util.Collection<String> onlyCodes)
            throws IOException, ReadDataException, ConfigException {
        Consumer<String> out = log == null ? System.out::println : log;
        long start = System.currentTimeMillis();

        Result result = new Result();
        result.days = days;

        // 看门狗：从检查本地数据来源目录那一刻就开始看着，任何一步超过 20 秒没有进展，
        // 就把全部线程堆栈写进 logs/hang-*.log，并在界面上说明卡在哪一步。
        HangWatchdog.start("盘后数据下载（最近 " + days + " 天）", out);
        try {
            Log.info("MainDownLoadFromTdx-start, 天数=" + days + ", 备份=" + backup);
            HangWatchdog.logLine("检查本地数据来源的 vipdoc 目录");
            out.accept("本地数据来源目录: " + TdxDataDir.requireVipdoc().getAbsolutePath());
            out.accept("数据库目录: " + MountConfig.currentDbDir());

            if (backup) {
                try {
                    result.backupFile = backupDb(out);
                    result.backedUp = !result.backupFile.isEmpty();
                } catch (Exception e) {
                    out.accept("备份失败（不影响下面的更新）: " + e.getMessage());
                    Log.error("备份失败：" + e.getMessage());
                }
            } else {
                out.accept("已选择不备份，直接更新");
            }

            out.accept("开始更新最近 " + days + " 天的数据（全市场逐只建库/写库，第一次跑需要几分钟）");
            describeEnvironment(out);
            ReaderFromTdx reader = ReaderFromTdx.getInstance();
            // 把界面日志口传下去：每 3 秒能看到「处理到第几只、最慢一路在干什么」。
            // 单只真挂在 native 上（磁盘/杀毒）时内部会跳过它、其余路继续，这件事只写 logs，不刷界面。
            reader.updateDataByDays(days, out, items, cancel, onlyCodes);

            result.elapsedMillis = System.currentTimeMillis() - start;
            out.accept("盘后数据下载完成，用时 " + (result.elapsedMillis / 1000) + " 秒");
            Log.info("MainDownLoadFromTdx-end");
            return result;
        } finally {
            HangWatchdog.stop();
        }
    }

    /** 同步盘 / 网络盘的路径关键字：这类目录逐个建库时最容易被云同步拖住 */
    private static final String[] SLOW_DISK_HINTS = {
            "onedrive", "dropbox", "nutstore", "坚果云", "百度网盘", "baidunetdisk",
            "icloud", "google drive", "googledrive", "阿里云盘", "aliyundrive",
            "微云", "weiyun", "天翼云盘", "189cloud", "移动云盘", "pcloud", "\\\\192.", "\\\\10."
    };

    /**
     * 开跑前给数据文件夹做个「体检」：空间够不够、写文件快不快、是不是在同步盘里。
     *
     * <p>盘后下载卡死那类问题，八成都出在盘上（同步盘占文件、网络盘掉线、杀毒实时扫描、
     * 盘满）。与其卡住以后猜，不如一开始就把这三个数打出来。</p>
     */
    private static void describeEnvironment(Consumer<String> out) {
        out.accept("并行度: " + Parallelism.describe(Parallelism.resolve())
                + "（config.properties 的 updater.threads=0 表示按核数自动算）");
        String dbDir = MountConfig.currentDbDir();
        if (dbDir == null || dbDir.trim().isEmpty()) {
            return;
        }
        File dir = new File(dbDir);
        out.accept("数据文件夹体检: " + dir.getAbsolutePath());
        try {
            long usable = dir.getUsableSpace();
            if (usable > 0) {
                out.accept("  可用空间: " + String.format("%.1f", usable / 1024.0 / 1024 / 1024) + " GB");
                if (usable < 2L * 1024 * 1024 * 1024) {
                    out.accept("  ⚠ 剩余空间不到 2GB：全市场建库大约要 1GB，建议先清盘再跑");
                }
            }
        } catch (Throwable ignored) {
            // 某些网络盘取不到空间，跳过
        }

        long cost;
        try {
            cost = writeProbe(dir);
        } catch (Throwable e) {
            out.accept("  ⚠ 写入测试失败: " + e + "（这个文件夹可能没有写权限）");
            return;
        }
        out.accept("  写入测试: 128KB 落盘用了 " + cost + " 毫秒"
                + (cost > 800
                ? "（偏慢：同步盘 / 网络盘 / 杀毒实时扫描都会这样，逐只建库时容易卡；"
                + "如果是机械盘，把 updater.threads 调到 2~4 反而更快）"
                : "（正常）"));

        String lower = dir.getAbsolutePath().toLowerCase();
        for (String hint : SLOW_DISK_HINTS) {
            if (lower.contains(hint)) {
                out.accept("  ⚠ 这个路径看起来在同步盘 / 网络盘里（" + hint + "）。"
                        + "全市场要建五千多个库文件，云同步会逐个扫一遍，很容易长时间卡住 —— "
                        + "建议换成本地磁盘上的文件夹。");
                break;
            }
        }
    }

    /** 写一个 128KB 的临时文件并强制落盘，返回耗时（毫秒） */
    private static long writeProbe(File dir) throws IOException {
        File probe = new File(dir, ".write_probe_" + System.nanoTime());
        long startMillis = System.currentTimeMillis();
        try (FileOutputStream fos = new FileOutputStream(probe)) {
            fos.write(new byte[128 * 1024]);
            fos.flush();
            fos.getFD().sync();
        } finally {
            if (probe.exists() && !probe.delete()) {
                probe.deleteOnExit();
            }
        }
        return System.currentTimeMillis() - startMillis;
    }

    /**
     * 控制台 / 菜单入口（无界面场景）。
     *
     * <p>图形界面走 {@link #run(int, boolean, Consumer)}，参数由画面在 EDT 上问好；
     * 这里用控制台输入，不再弹 Swing 对话框 —— 任务可能在后台线程里，弹框会出问题。</p>
     */
    public static void main(String[] args) throws Exception {
        int days = 5;
        boolean backup = false;
        boolean ask = args == null || args.length < 2;
        if (!ask) {
            try {
                days = Integer.parseInt(args[0].trim());
                backup = "true".equalsIgnoreCase(args[1].trim());
            } catch (NumberFormatException e) {
                System.out.println("参数格式错误，应为: <天数> <是否备份 true/false>");
                return;
            }
        } else {
            Scanner scanner = new Scanner(System.in);
            System.out.print("请输入要更新的天数（1-1000，回车=5）: ");
            String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            if (!line.isEmpty()) {
                try {
                    days = Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    System.out.println("请输入有效的数字");
                    return;
                }
            }
            if (days < 1 || days > 1000) {
                System.out.println("天数必须在 1-1000 之间");
                return;
            }
            System.out.print("是否先备份数据库？(y/N): ");
            String backupLine = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            backup = "y".equalsIgnoreCase(backupLine);
        }

        run(days, backup, System.out::println);
    }
}
