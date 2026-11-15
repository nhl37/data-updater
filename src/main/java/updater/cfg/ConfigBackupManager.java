package updater.cfg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 配置文件的“原始备份”管理
 *
 * 规则：
 * 1. 首次打开配置画面时，若原始备份不存在，则把当前 config.properties 复制为
 *    config.properties.origin（之后不再改动，始终保留最初的配置内容）
 * 2. 点击“恢复原始配置”时：
 *    - 先把当前 config.properties 另存为 config.properties.bak_yyyyMMdd_HHmmss（防止误操作丢数据）
 *    - 再用 config.properties.origin 覆盖 config.properties
 */
public class ConfigBackupManager {

    /** 原始备份文件后缀，即 config.properties.origin */
    public static final String ORIGIN_SUFFIX = ".origin";

    private final File sourceFile;
    private final File originFile;

    public ConfigBackupManager(File sourceFile) {
        this.sourceFile = sourceFile == null ? null : sourceFile.getAbsoluteFile();
        if (this.sourceFile == null) {
            this.originFile = null;
        } else {
            File parent = this.sourceFile.getParentFile();
            String name = this.sourceFile.getName() + ORIGIN_SUFFIX;
            this.originFile = (parent == null) ? new File(name) : new File(parent, name);
        }
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public File getOriginFile() {
        return originFile;
    }

    /**
     * 原始备份是否存在
     */
    public boolean hasOriginBackup() {
        return originFile != null && originFile.isFile();
    }

    /**
     * 确保原始备份存在（首次调用时创建）
     *
     * @return true 表示本次新建了备份；false 表示备份已存在
     */
    public boolean ensureOriginBackup() throws ConfigException {
        if (sourceFile == null) {
            throw new ConfigException("配置文件对象为null");
        }
        if (originFile == null) {
            throw new ConfigException("原始备份文件路径计算失败");
        }
        if (originFile.isFile()) {
            return false;
        }
        if (!sourceFile.isFile()) {
            throw new ConfigException("配置文件不存在: " + sourceFile.getAbsolutePath());
        }
        copy(sourceFile, originFile);
        return true;
    }

    /**
     * 原始备份的创建（最后修改）时间
     */
    public String getOriginCreateTime() {
        if (!hasOriginBackup()) {
            return "(无)";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(originFile.lastModified()));
    }

    /**
     * 把当前配置文件另存为 .bak_时间戳 文件
     */
    public File backupCurrent() throws ConfigException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new ConfigException("当前配置文件不存在，无法备份");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File parent = sourceFile.getParentFile();
        String name = sourceFile.getName() + ".bak_" + stamp;
        File bak = (parent == null) ? new File(name) : new File(parent, name);
        copy(sourceFile, bak);
        return bak;
    }

    /**
     * 用原始备份覆盖当前配置文件
     *
     * @return 覆盖前对当前配置做的备份文件
     */
    public File restoreOrigin() throws ConfigException {
        if (!hasOriginBackup()) {
            throw new ConfigException("原始配置备份不存在: "
                    + (originFile == null ? "(路径未知)" : originFile.getAbsolutePath()));
        }
        File currentBackup = backupCurrent();
        copy(originFile, sourceFile);
        return currentBackup;
    }

    private static void copy(File from, File to) throws ConfigException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();
        } catch (IOException e) {
            throw new ConfigException("复制文件失败: " + from.getAbsolutePath()
                    + " -> " + to.getAbsolutePath() + ", " + e.getMessage());
        }
    }
}