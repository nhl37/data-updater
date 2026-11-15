package updater.main.updater;

import updater.ui.UpdaterTheme;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigFileEditor;
import updater.cfg.PythonInterpreter;
import updater.fetchdata.tdx.TdxDataDir;
import updater.net.Redial;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;

/**
 * 「路径设置」：数据文件夹（只读）/ 本地数据来源目录 / 数据备份文件夹 / python 解释器 / 换 IP 命令。
 *
 * <p>这四行原来常驻在更新画面顶部，占了将近一半高度，而它们一年也改不了一次 ——
 * 真正每天要看的是「跑到哪了、哪只失败了」。现在挪进这个对话框，
 * 主画面只留一条状态栏显示当前值。</p>
 *
 * <p>保存语义（<b>选完即保存，界面上没有「保存」按钮</b>）：</p>
 * <ul>
 *   <li>点「浏览…」选完目录、点「自动检测」选中解释器，<b>当场写盘</b>，不用再点一次保存。</li>
 *   <li>手动输入路径时，按回车或光标离开输入框即保存；没改动不会重复写文件。</li>
 *   <li>路径没通过校验 / 用户在确认框里取消时，输入框回退到上一次已保存的值，
 *       避免界面显示一个其实没生效的路径。</li>
 *   <li>数据文件夹：<b>只读</b>。由外部程序启动时用 {@code --data-dir} 传入，
 *       更新器不读配置、也不允许在这里改 —— 改这里就会出现「更新器写一处、调用方读另一处」。</li>
 *   <li>本地数据来源目录：写回 {@code updater.tdx.path}（更新器自己的配置项，默认空），
 *       本进程立即生效；调用方的 {@code tdx.path} 不受影响。</li>
 *   <li>数据备份文件夹：写回 {@code data.path.dbbak}，本进程立即生效。</li>
 *   <li>换 IP 命令：写回 {@code net.redial.command}，留空就把这个键删掉（＝不换 IP）。
 *       它只在 5 分钟线三条线路<b>一只都取不到</b>时被执行一次。</li>
 * </ul>
 */
public final class PathSettingsDialog extends JDialog {

    private static final Font LABEL_FONT = updater.ui.UpdaterTheme.regular(12f);
    private static final Color HINT_COLOR = updater.ui.UpdaterTheme.MUTED;
    private static final Color OK_COLOR = UpdaterTheme.OK;
    private static final Color WARN_COLOR = UpdaterTheme.WARN_TEXT;

    /** 任一保存成功后通知主画面刷状态栏 */
    private final Runnable onChanged;

    private final JTextField dataDirField = new JTextField();
    private final JLabel dataDirStatus = new JLabel();

    private final JTextField tdxDirField = new JTextField();
    private final JLabel tdxDirStatus = new JLabel();
    /** 上一次成功保存的值，用来判断「有没有改动」以及失败时回退 */
    private String tdxSaved = "";

    private final JTextField backupDirField = new JTextField();
    private final JLabel backupDirStatus = new JLabel();
    private String backupSaved = "";

    private final JTextField pythonExeField = new JTextField();
    private final JLabel pythonExeStatus = new JLabel();
    private String pythonSaved = "";

    private final JTextField redialField = new JTextField();
    private final JLabel redialStatus = new JLabel();
    private String redialSaved = "";
    /** 「试一次」正在跑：挡住重复点击，也挡住失焦保存把状态行盖掉 */
    private boolean testing;

    /** 保存过程中弹确认框会让输入框失焦，用它挡住重入 */
    private boolean saving;

    public PathSettingsDialog(Frame owner, Runnable onChanged) {
        super(owner, "路径设置", true);
        this.onChanged = onChanged;

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(updater.ui.UpdaterTheme.BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);

        rows.add(createDataDirRow());
        rows.add(Box.createVerticalStrut(10));
        rows.add(new JSeparator());
        rows.add(Box.createVerticalStrut(10));
        rows.add(createTdxDirRow());
        rows.add(Box.createVerticalStrut(10));
        rows.add(new JSeparator());
        rows.add(Box.createVerticalStrut(10));
        rows.add(createBackupDirRow());
        rows.add(Box.createVerticalStrut(10));
        rows.add(new JSeparator());
        rows.add(Box.createVerticalStrut(10));
        rows.add(createPythonRow());
        rows.add(Box.createVerticalStrut(10));
        rows.add(new JSeparator());
        rows.add(Box.createVerticalStrut(10));
        rows.add(createRedialRow());

        content.add(rows, BorderLayout.CENTER);

        JButton close = updater.ui.UpdaterKit.button("关闭", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        JLabel autoHint = new JLabel("选择目录后立即保存，无需再点保存");
        autoHint.setFont(updater.ui.UpdaterTheme.regular(11f));
        autoHint.setForeground(HINT_COLOR);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeRow.setOpaque(false);
        closeRow.add(close);
        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setOpaque(false);
        bottom.add(autoHint, BorderLayout.WEST);
        bottom.add(closeRow, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        setContentPane(content);
        setSize(760, 610);
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(620, 560));
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                dataDirField.requestFocusInWindow();
            }
        });
    }

    // ================================================================ 数据文件夹（只读）

    private JPanel createDataDirRow() {
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);

        dataDirField.setFont(LABEL_FONT);
        dataDirField.setText(UpdaterDataDir.root());
        // 只读：数据目录由外部程序启动时传进来，更新器里改不了
        dataDirField.setEditable(false);
        dataDirField.setBackground(updater.ui.UpdaterTheme.BG);
        dataDirField.setToolTipText("由启动参数 --data-dir 传入，这里只显示、不能修改。"
                + "要换目录请用 --data-dir 重新指定");

        field.add(title("数据文件夹"), BorderLayout.WEST);
        field.add(dataDirField, BorderLayout.CENTER);

        dataDirStatus.setFont(updater.ui.UpdaterTheme.regular(11f));
        refreshDataDirStatus();
        return row(field, dataDirStatus);
    }

    private void refreshDataDirStatus() {
        String root = UpdaterDataDir.root();
        UpdaterDataDir.Source source = UpdaterDataDir.source();
        String stockDir = updater.mount.DataRoot.stockDirOf(root);
        int count = updater.mount.DataRoot.countStockDbs(new File(stockDir));
        String origin = source == UpdaterDataDir.Source.ARGUMENT
                ? "由启动参数 --data-dir 传入，这里只读"
                : "没收到 --data-dir，用程序目录下的 data";
        if (!new File(root).isDirectory()) {
            dataDirStatus.setForeground(WARN_COLOR);
            dataDirStatus.setText("（" + source.label() + "）" + origin + "；文件夹还不存在，执行更新时会自动创建"
                    + "（个股库会建在 " + stockDir + "）");
        } else {
            dataDirStatus.setForeground(count > 0 ? OK_COLOR : HINT_COLOR);
            dataDirStatus.setText("（" + source.label() + "）" + origin
                    + "；股票库目录: " + stockDir + "（" + count + " 个库）");
        }
    }

    // ================================================================ 本地数据来源目录

    private JPanel createTdxDirRow() {
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);

        tdxDirField.setFont(LABEL_FONT);
        tdxDirField.setText(TdxDataDir.configuredPath());
        tdxSaved = tdxDirField.getText();
        tdxDirField.setToolTipText("更新器单独配置，默认空；"
                + "选择目录后立即保存，本更新程序立即生效");
        tdxDirField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveTdxDir(false);
            }
        });
        tdxDirField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!saving) {
                    saveTdxDir(true);
                }
            }
        });

        JButton browse = updater.ui.UpdaterKit.button("浏览…", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                File chosen = chooseDirectory("选择本地数据来源目录", tdxDirField.getText());
                if (chosen != null) {
                    tdxDirField.setText(chosen.getAbsolutePath().replace('\\', '/'));
                    saveTdxDir(false);
                }
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(browse);

        field.add(title("本地数据来源"), BorderLayout.WEST);
        field.add(tdxDirField, BorderLayout.CENTER);
        field.add(buttons, BorderLayout.EAST);

        tdxDirStatus.setFont(updater.ui.UpdaterTheme.regular(11f));
        tdxDirStatus.setForeground(HINT_COLOR);
        tdxDirStatus.setText(TdxDataDir.configuredPath().isEmpty()
                ? "未配置：读取本地数据的任务会提示先选择目录（选完即保存）"
                : "选完目录即自动保存，无需再点保存");
        return row(field, tdxDirStatus);
    }

    /** @param quiet 失焦自动保存＝true，不弹窗、失败就回退并在状态行说明 */
    private boolean saveTdxDir(boolean quiet) {
        if (saving) {
            return true;
        }
        saving = true;
        try {
            String raw = tdxDirField.getText().trim();
            if (samePath(raw, tdxSaved)) {
                return true;
            }
            if (raw.isEmpty()) {
                hint(tdxDirStatus, "请先选择本地数据来源目录，已保留原设置");
                tdxDirField.setText(tdxSaved);
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "请先选择本地数据来源目录。",
                            "本地数据来源", JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
            File dir = new File(raw);
            if (!dir.isDirectory()) {
                hint(tdxDirStatus, "目录不存在，已保留原设置");
                tdxDirField.setText(tdxSaved);
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "目录不存在：" + dir.getAbsolutePath()
                                    + "\n请重新选择本地数据来源目录。",
                            "本地数据来源", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
            if (!new File(dir, "vipdoc").isDirectory()) {
                if (quiet) {
                    hint(tdxDirStatus, "目录下没有 vipdoc，已保留原设置");
                    tdxDirField.setText(tdxSaved);
                    return false;
                }
                int answer = JOptionPane.showConfirmDialog(this,
                        "所选目录下没有 vipdoc 子目录：\n" + dir.getAbsolutePath()
                                + "\n\n仍要用这个目录吗？",
                        "本地数据来源", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    tdxDirField.setText(tdxSaved);
                    tdxDirStatus.setForeground(HINT_COLOR);
                    tdxDirStatus.setText("已取消，保留原来的目录");
                    return false;
                }
            }
            String path = dir.getAbsolutePath().replace('\\', '/');
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configFile());
                editor.load();
                editor.addEntry(TdxDataDir.configKey(), path);
                editor.save();
                AppConfigManager.getInstance().getConfigReader().reload();
                tdxDirField.setText(path);
                tdxSaved = path;
                tdxDirStatus.setForeground(OK_COLOR);
                tdxDirStatus.setText("已保存到 config.properties，本更新程序立即生效");
                notifyChanged();
                return true;
            } catch (Exception ex) {
                tdxDirField.setText(tdxSaved);
                hint(tdxDirStatus, "保存失败，已保留原设置：" + ex.getMessage());
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "保存本地数据来源目录失败: " + ex.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        } finally {
            saving = false;
        }
    }

    // ================================================================ 备份目录

    private JPanel createBackupDirRow() {
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);

        backupDirField.setFont(LABEL_FONT);
        backupDirField.setText(BackupDir.configured());
        backupSaved = backupDirField.getText();
        backupDirField.setToolTipText("盘后数据下载勾选「先备份」时，数据库压缩包存到这里"
                + "（配置项 data.path.dbbak）；选择目录后立即保存并写回 config.properties");
        backupDirField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveBackupDir(false);
            }
        });
        backupDirField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!saving) {
                    saveBackupDir(true);
                }
            }
        });

        JButton browse = updater.ui.UpdaterKit.button("浏览…", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                File chosen = chooseDirectory("选择数据备份文件夹", backupDirField.getText());
                if (chosen != null) {
                    backupDirField.setText(chosen.getAbsolutePath().replace('\\', '/'));
                    saveBackupDir(false);
                }
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(browse);

        field.add(title("数据备份文件夹"), BorderLayout.WEST);
        field.add(backupDirField, BorderLayout.CENTER);
        field.add(buttons, BorderLayout.EAST);

        backupDirStatus.setFont(updater.ui.UpdaterTheme.regular(11f));
        backupDirStatus.setForeground(HINT_COLOR);
        backupDirStatus.setText(BackupDir.configured().isEmpty()
                ? "未配置：盘后数据下载勾选「先备份」时会提示先设置这里（选完即保存）"
                : "选完目录即自动保存，无需再点保存");
        return row(field, backupDirStatus);
    }

    private boolean saveBackupDir(boolean quiet) {
        if (saving) {
            return true;
        }
        saving = true;
        try {
            String raw = BackupDir.normalize(backupDirField.getText());
            if (samePath(raw, backupSaved)) {
                return true;
            }
            if (raw.isEmpty()) {
                hint(backupDirStatus, "数据备份文件夹不能为空，已保留原设置");
                backupDirField.setText(backupSaved);
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "数据备份文件夹不能为空。",
                            "数据备份文件夹", JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
            File dir = new File(raw);
            if (dir.exists() && !dir.isDirectory()) {
                hint(backupDirStatus, "这是文件不是文件夹，已保留原设置");
                backupDirField.setText(backupSaved);
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "这个路径是文件，不是文件夹：\n" + dir.getAbsolutePath(),
                            "数据备份文件夹", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
            if (!dir.isDirectory()) {
                if (quiet) {
                    hint(backupDirStatus, "文件夹不存在，已保留原设置；要用它请点「浏览…」重新选一次");
                    backupDirField.setText(backupSaved);
                    return false;
                }
                int answer = JOptionPane.showConfirmDialog(this,
                        "这个文件夹还不存在：\n" + dir.getAbsolutePath() + "\n\n现在就创建并使用它吗？",
                        "数据备份文件夹", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    backupDirField.setText(backupSaved);
                    backupDirStatus.setForeground(HINT_COLOR);
                    backupDirStatus.setText("已取消，保留原来的文件夹");
                    return false;
                }
            }
            try {
                // BackupDir.save 自己会建 ConfigFileEditor，先保证配置文件存在
                configFile();
                File saved = BackupDir.save(raw);
                String path = saved.getAbsolutePath().replace('\\', '/');
                backupDirField.setText(path);
                backupSaved = path;
                backupDirStatus.setForeground(OK_COLOR);
                backupDirStatus.setText("已保存到 config.properties，本更新程序立即生效");
                notifyChanged();
                return true;
            } catch (Exception ex) {
                backupDirField.setText(backupSaved);
                hint(backupDirStatus, "保存失败，已保留原设置：" + ex.getMessage());
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "保存数据备份文件夹失败: " + ex.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        } finally {
            saving = false;
        }
    }

    // ================================================================ python 解释器

    /**
     * python 解释器 —— 除权、财报、股本三个任务都要用它跑脚本。
     *
     * <p>留空 = 自动探测（python3 → python → py）。用户机器上装了多个 python、
     * 而只有某个环境里装了 pytdx / pandas / akshare 时，在这里指定那一个。</p>
     */
    private JPanel createPythonRow() {
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);

        pythonExeField.setFont(LABEL_FONT);
        pythonExeField.setText(PythonInterpreter.configured());
        pythonSaved = pythonExeField.getText();
        pythonExeField.setToolTipText("留空自动探测 python3 / python / py。"
                + "除权 / 财报 / 股本脚本依赖 pytdx、pandas、akshare，"
                + "装在哪个 python 环境里就填哪个解释器；选择后立即保存");
        pythonExeField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                savePythonExe(false);
            }
        });
        pythonExeField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!saving) {
                    savePythonExe(true);
                }
            }
        });

        JButton detect = updater.ui.UpdaterKit.button("自动检测", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String found = PythonInterpreter.detect();
                if (found == null) {
                    pythonExeField.setText("");
                    pythonExeStatus.setForeground(WARN_COLOR);
                    pythonExeStatus.setText("没检测到 python：请先安装 python 3，或点「浏览…」指定解释器");
                    return;
                }
                pythonExeField.setText(found);
                savePythonExe(false);
            }
        });
        JButton browse = updater.ui.UpdaterKit.button("浏览…", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                File chosen = chooseFile("选择 python 解释器", pythonExeField.getText());
                if (chosen != null) {
                    pythonExeField.setText(chosen.getAbsolutePath().replace('\\', '/'));
                    savePythonExe(false);
                }
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(detect);
        buttons.add(browse);

        field.add(title("python 解释器"), BorderLayout.WEST);
        field.add(pythonExeField, BorderLayout.CENTER);
        field.add(buttons, BorderLayout.EAST);

        pythonExeStatus.setFont(updater.ui.UpdaterTheme.regular(11f));
        refreshPythonStatus();
        return row(field, pythonExeStatus);
    }

    private void refreshPythonStatus() {
        String configured = PythonInterpreter.configured();
        String working = PythonInterpreter.resolve();
        if (working == null) {
            pythonExeStatus.setForeground(WARN_COLOR);
            pythonExeStatus.setText("没找到 python：除权 / 财报 / 股本任务无法执行，请安装 python 3 或点「自动检测」");
            return;
        }
        if (!configured.isEmpty() && !configured.equals(working)) {
            pythonExeStatus.setForeground(WARN_COLOR);
            pythonExeStatus.setText("这里填的 " + configured + " 跑不起来，当前实际使用 " + working);
            return;
        }
        pythonExeStatus.setForeground(OK_COLOR);
        pythonExeStatus.setText(configured.isEmpty()
                ? "未指定，自动使用 " + working + "（留空就行）"
                : "使用 " + working);
    }

    private boolean savePythonExe(boolean quiet) {
        if (saving) {
            return true;
        }
        saving = true;
        try {
            String raw = pythonExeField.getText().trim();
            if (samePath(raw, pythonSaved)) {
                return true;
            }
            if (!raw.isEmpty() && !PythonInterpreter.probe(raw)) {
                if (quiet) {
                    pythonExeField.setText(pythonSaved);
                    pythonExeStatus.setForeground(WARN_COLOR);
                    pythonExeStatus.setText("这个解释器跑不起来，已保留原设置：" + raw);
                    return false;
                }
                int answer = JOptionPane.showConfirmDialog(this,
                        "这个解释器跑不起来：\n" + raw
                                + "\n\n仍要保存吗？（保存后相关任务会照样失败）",
                        "python 解释器", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    pythonExeField.setText(pythonSaved);
                    pythonExeStatus.setForeground(HINT_COLOR);
                    pythonExeStatus.setText("已取消，保留原来的解释器");
                    return false;
                }
            }
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configFile());
                editor.load();
                editor.addEntry(PythonInterpreter.KEY, raw);
                editor.save();
                AppConfigManager.getInstance().getConfigReader().reload();
                // 探测结果有缓存，保存后必须清掉，否则状态行还显示旧结论
                PythonInterpreter.invalidate();
                pythonExeField.setText(raw);
                pythonSaved = raw;
                refreshPythonStatus();
                notifyChanged();
                return true;
            } catch (Exception ex) {
                pythonExeField.setText(pythonSaved);
                pythonExeStatus.setForeground(WARN_COLOR);
                pythonExeStatus.setText("保存失败，已保留原设置：" + ex.getMessage());
                if (!quiet) {
                    JOptionPane.showMessageDialog(this, "保存 python 解释器失败: " + ex.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        } finally {
            saving = false;
        }
    }

    // ================================================================ 换 IP 命令（可选）

    /**
     * 5 分钟线三家一条都没取到时，跑一次用户填的这行命令来换 IP。
     *
     * <p><b>为什么是个空框而不是「路由器型号下拉」</b>：重拨的地址、认证参数各家各代都不同，
     * 内置型号必然漏，还等于要用户把路由器账号密码交给程序。这里只留一行「执行什么」，
     * 用户自己填，程序不碰任何密码。</p>
     *
     * <p>留空＝完全关掉这件事，程序行为跟以前一样。</p>
     */
    private JPanel createRedialRow() {
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);

        redialField.setFont(LABEL_FONT);
        redialField.setText(Redial.configured());
        redialSaved = redialField.getText();
        redialField.setToolTipText("留空＝不换 IP。填一行本机命令，只有 5 分钟线三家都取不到时才跑它一次。"
                + "例如小米/Redmi 路由器："
                + "curl -s \"http://192.168.1.1/cgi-bin/luci/;stok=你的stok/api/xqnetwork/pppoe_stop\""
                + " ; curl -s \"http://192.168.1.1/cgi-bin/luci/;stok=你的stok/api/xqnetwork/pppoe_start\""
                + "（地址与 stok 按自己路由器改；执行时会断网约半分钟）");
        redialField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveRedial(false);
            }
        });
        redialField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!saving && !testing) {
                    saveRedial(true);
                }
            }
        });

        JButton test = updater.ui.UpdaterKit.button("试一次", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testRedial();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(test);

        field.add(title("换 IP 命令"), BorderLayout.WEST);
        field.add(redialField, BorderLayout.CENTER);
        field.add(buttons, BorderLayout.EAST);

        redialStatus.setFont(updater.ui.UpdaterTheme.regular(11f));
        refreshRedialStatus();
        return row(field, redialStatus);
    }

    private void refreshRedialStatus() {
        redialStatus.setForeground(HINT_COLOR);
        redialStatus.setText(redialSaved.isEmpty()
                ? "未设置：5 分钟线取不到数据时不会动 IP"
                : "已保存：只有 5 分钟线一只都取不到时才执行一次（会断网约半分钟）");
    }

    private boolean saveRedial(boolean quiet) {
        if (saving) {
            return true;
        }
        String raw = redialField.getText().trim();
        if (raw.equals(redialSaved)) {
            return true;
        }
        saving = true;
        try {
            ConfigFileEditor editor = new ConfigFileEditor(configFile());
            editor.load();
            if (raw.isEmpty()) {
                // 空就是不启用：把键删掉，config.properties 里不留一行没用的空值
                editor.deleteEntry(Redial.KEY);
            } else {
                editor.addEntry(Redial.KEY, raw);
            }
            editor.save();
            AppConfigManager.getInstance().getConfigReader().reload();
            redialField.setText(raw);
            redialSaved = raw;
            redialStatus.setForeground(OK_COLOR);
            redialStatus.setText(raw.isEmpty()
                    ? "已清空：不会自动换 IP"
                    : "已保存到 config.properties，本更新程序立即生效");
            notifyChanged();
            return true;
        } catch (Exception ex) {
            redialField.setText(redialSaved);
            hint(redialStatus, "保存失败，已保留原设置：" + ex.getMessage());
            if (!quiet) {
                JOptionPane.showMessageDialog(this, "保存换 IP 命令失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        } finally {
            saving = false;
        }
    }

    /** 「试一次」：真会断网半分钟，所以先问一句再跑 */
    private void testRedial() {
        if (testing) {
            return;
        }
        if (!saveRedial(false)) {
            return;
        }
        if (redialSaved.isEmpty()) {
            hint(redialStatus, "先在框里填一行重拨命令，再点「试一次」");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "现在执行一次这条命令？\n\n" + redialSaved
                        + "\n\n如果它真的是重拨，网络会断约半分钟，正在跑的更新会失败。",
                "换 IP 命令", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        testing = true;
        redialStatus.setForeground(HINT_COLOR);
        redialStatus.setText("正在执行…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Redial.redialAndWait(new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String message) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                redialStatus.setForeground(HINT_COLOR);
                                redialStatus.setText(message);
                            }
                        });
                    }
                }, null);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        testing = false;
                        redialStatus.setForeground(ok ? OK_COLOR : WARN_COLOR);
                        redialStatus.setText(ok
                                ? "已执行，网络已恢复；真被限速时程序也会这样自动换一次"
                                : "命令跑完了但网络没恢复（或命令没跑起来），原因见日志");
                    }
                });
            }
        }, "redial-test");
        worker.setDaemon(true);
        worker.start();
    }

    // ================================================================ 小工具

    /** 配置文件可能还没建（程序和配置不在一处时），保存前先补一个空文件 */
    private File configFile() throws IOException {
        File file = new File(UpdaterMain.currentHome(), "config.properties");
        if (!file.isFile() && !file.createNewFile()) {
            throw new IOException("无法创建 " + file.getAbsolutePath());
        }
        return file;
    }

    /** 路径统一斜杠、去尾斜杠后比较，避免「同一个目录」反复写盘 */
    private boolean samePath(String a, String b) {
        return BackupDir.normalize(a).equals(BackupDir.normalize(b));
    }

    /** 校验没过 / 保存失败：状态行用警示色写清原因 */
    private void hint(JLabel status, String text) {
        status.setForeground(WARN_COLOR);
        status.setText(text);
    }

    private JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(updater.ui.UpdaterTheme.bold(12f));
        label.setForeground(updater.ui.UpdaterTheme.TEXT_STRONG);
        label.setPreferredSize(new Dimension(120, 22));
        return label;
    }

    /** 一行输入 + 下面一行灰色说明 */
    private JPanel row(JPanel field, JLabel status) {
        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setOpaque(false);
        wrap.add(field, BorderLayout.CENTER);
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.setBorder(BorderFactory.createEmptyBorder(0, 128, 0, 0));
        statusRow.add(status, BorderLayout.CENTER);
        wrap.add(statusRow, BorderLayout.SOUTH);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
        return wrap;
    }

    private File chooseDirectory(String title, String current) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(title);
        if (current != null && !current.trim().isEmpty()) {
            File dir = new File(current.trim());
            if (dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            } else if (dir.getParentFile() != null && dir.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(dir.getParentFile());
            }
        }
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile() : null;
    }

    private File chooseFile(String title, String current) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setDialogTitle(title);
        if (current != null && !current.trim().isEmpty()) {
            File file = new File(current.trim());
            File dir = file.isDirectory() ? file : file.getParentFile();
            if (dir != null && dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            }
        }
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile() : null;
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }
}
