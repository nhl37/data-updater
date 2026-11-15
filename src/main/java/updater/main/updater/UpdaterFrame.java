package updater.main.updater;

import updater.f10.AnnouncementFeed;
import updater.fetchdata.LatestPriceUpdater;
import updater.fetchdata.capital.ShareCapitalSyncService;
import updater.fetchdata.eastmoney.EastMoneyDayFetcher;
import updater.fetchdata.min.Min5Downloader;
import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.fetchdata.tdx.finance.FinanceImportService;
import updater.holder.HolderStore;
import updater.holder.HolderSync;
import updater.main.down.MainDownLoadFromTdx;
import updater.log.Log;
import updater.pit.PitSync;
import updater.risk.RiskStore;
import updater.risk.RiskSync;
import updater.ui.UpdaterTheme;
import updater.ui.UpdaterKit;
import updater.util.HangWatchdog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据更新程序的主画面。
 *
 * <p>设计目标是三件事，按重要程度排：</p>
 * <ol>
 *   <li><b>看得见</b>：不管哪个任务，右边都有一张台账，一只股票 / 一个季度一行，
 *       状态（未开始 / 进行中 / 完成 / 跳过 / 失败）和原因都写在行里。不再是
 *       「一个转圈的模态框 + 一句心跳文本」。</li>
 *   <li><b>断得住、接得上</b>：台账每 0.8 秒落一次盘，任务被关窗口 / kill / 断电打断后，
 *       下次启动顶部会提示「上次跑到哪、还剩多少」，一键接着跑剩下的。</li>
 *   <li><b>失败能处理</b>：失败的行单独筛出来看，「重试失败」只重跑这些，
 *       「导出清单」给一份 CSV 可以发出来。</li>
 * </ol>
 *
 * <p>参数全部内联在任务标题行右侧（天数 / 备份 / 除权来源 / 财报季度），
 * 不再一层层弹 {@code JOptionPane} 问。</p>
 *
 * <p>左栏按数据类别分成「股价数据 / 财报与股东 / 研究数据 / 其他数据」四组。
 * 前三组标题上有「查缺补漏」：股价只核日线，财报与股东按报告期逐季对账，其他核名称库。
 * 研究数据是派生的风险 / 估值数据，没有对账口径，所以不挂检查按钮。
 * 检查任务都还是任务（有台账、能续跑、能翻历史），但不占组内的任务按钮。</p>
 */
public class UpdaterFrame extends JFrame {

    // ------------------------------------------------------------------ 配色

    private static final Color OK_COLOR = UpdaterTheme.OK;
    private static final Color FAIL_COLOR = UpdaterTheme.FAIL;
    private static final Color LOG_BG = UpdaterTheme.SURFACE;

    private static final Font TASK_TITLE_FONT = UpdaterTheme.bold(14f);
    private static final Font SMALL = UpdaterTheme.regular(11f);
    private static final Font BODY = UpdaterTheme.regular(12f);

    /** 股价数据查缺补漏的检查范围（下标跟 {@link #INTEGRITY_RANGE_YEARS} 对齐） */
    private static final String[] INTEGRITY_RANGES = {
            "全部历史", "最近 10 年", "最近 5 年", "最近 3 年", "最近 1 年"};
    /** 0 = 不设下限 */
    private static final int[] INTEGRITY_RANGE_YEARS = {0, 10, 5, 3, 1};
    /** 财报与股东查缺补漏的检查范围（下标跟 {@link #FINANCE_CHECK_YEARS} 对齐） */
    private static final String[] FINANCE_CHECK_RANGES = {
            "最近 1 年", "最近 3 年", "最近 5 年", "最近 10 年"};
    private static final int[] FINANCE_CHECK_YEARS = {1, 3, 5, 10};
    /** 流通股东一次同步几个季度（一季约 5 万行） */
    private static final String[] HOLDER_RANGES = {
            "最近 1 个季度", "最近 4 个季度", "最近 8 个季度", "最近 16 个季度"};
    private static final int[] HOLDER_COUNTS = {1, 4, 8, 16};

    // ------------------------------------------------------------------ 运行状态

    private UpdateTask selectedTask = UpdateTask.POST_DOWNLOAD;
    private UpdateLedger ledger;
    private volatile boolean running;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Thread worker;
    private long runStartMillis;
    /** 任务跑完后要做的事（同步公告等），可为 null */
    private Runnable afterFinish;
    /** 财报区间检查的结果，交给跑批那步用 */
    private List<FinanceImportService.PrepareResult> pendingFinance;

    // ------------------------------------------------------------------ 界面

    private final LedgerTableModel tableModel = new LedgerTableModel();
    private JTable table;
    private CardLayout tableCards;
    private JPanel tableHolder;
    private JLabel emptyTableLabel;

    private JLabel taskTitleLabel;
    private JLabel taskSubtitleLabel;
    private JPanel paramZone;
    private JButton startButton;
    private JButton stopButton;
    private JButton retryButton;
    private JButton exportButton;

    private JProgressBar progressBar;
    private JLabel countsLabel;
    private JLabel currentLabel;
    private JLabel resultLabel;

    private final Map<LedgerTableModel.Filter, JToggleButton> chips =
            new LinkedHashMap<LedgerTableModel.Filter, JToggleButton>();
    private JTextField searchField;

    private JPanel banner;
    private JPanel bannerWrap;
    private JPanel bannerTextHolder;
    private UpdateLedger.Resume interrupted;

    private ButtonGroup taskButtonGroup;
    private final Map<UpdateTask, TaskNavButton> taskButtons =
            new LinkedHashMap<UpdateTask, TaskNavButton>();
    /** 三个分组标题上的「查缺补漏」按钮，当前正在看哪个组就高亮哪个 */
    private final Map<UpdateTask.Group, JButton> checkButtons =
            new LinkedHashMap<UpdateTask.Group, JButton>();
    private JPanel historyPanel;

    private JTextArea logArea;
    private JScrollPane logScroll;
    private JButton logToggle;

    private JLabel externalIpLabel;

    private JLabel statusDataDir;
    private JLabel statusLedgerDir;

    // 各任务的参数控件
    private JSpinner daysSpinner;
    private JCheckBox backupCheck;
    /** 盘后下载写完日线顺带补 5 分钟线（默认勾选，取消就只动日线） */
    private JCheckBox minCheck;
    /** 公告同步的回溯天数 */
    private JSpinner announceDaysSpinner;
    /** 股价查缺补漏：要不要连 5 分钟线一起核 */
    private JCheckBox checkMin5Box;
    /** 股价查缺补漏：本地没有分钟文件时，允许走在线线路补 */
    private JCheckBox onlineMin5Box;

    /** 数据来源面板：把当前任务的默认与备用线路全列出来，用户可以直接点选 */
    private JPanel sourceZone;
    private ButtonGroup sourceGroup;
    /** 本次选中的线路；切任务时由 {@link #buildSourceZone} 重置成「自动」，不跨任务记住 */
    private String selectedSourceId = UpdateSources.AUTO;

    private JTextField financeStart;
    private JTextField financeEnd;
    private JComboBox<String> rangeCombo;
    private JComboBox<String> financeRangeCombo;
    private JCheckBox refreshHoldersCheck;
    private JComboBox<String> holderRangeCombo;
    private JCheckBox holderSkipCheck;

    /** 上次体检用的时间下限（yyyyMMdd，0 = 全部历史），修复时沿用 */
    private long integrityMinTime;
    /** 上次财报体检的范围与「修复时刷股东」勾选，修复时沿用 */
    private int financeCheckYears = FinanceIntegrityChecker.DEFAULT_YEARS;
    private boolean refreshHoldersOnRepair = true;
    /** 三组体检各自的汇总，用来写结果行的原话 */
    private DataIntegrityChecker.Summary lastIntegritySummary;
    private FinanceIntegrityChecker.Summary lastFinanceSummary;
    private NameIntegrityChecker.Summary lastNameSummary;
    /** 本次跑的是「修复」而不是「体检」，结果行文案不一样 */
    private boolean runningRepair;

    // 日志重定向
    private PrintStream originalOut;
    private PrintStream originalErr;

    public UpdaterFrame() {
        setTitle(updater.ui.UpdaterInfo.windowTitle());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        UpdaterKit.fitToScreen(this, 1180, 820, 1000, 640);
        getContentPane().setBackground(UpdaterTheme.BG);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UpdaterTheme.BG);
        root.add(createHeader(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(10, UpdaterTheme.GUTTER, 12, UpdaterTheme.GUTTER));
        body.add(createBanner(), BorderLayout.NORTH);

        JPanel columns = new JPanel(new BorderLayout(UpdaterTheme.GUTTER, 0));
        columns.setOpaque(false);
        columns.add(createLeftColumn(), BorderLayout.WEST);
        columns.add(createWorkArea(), BorderLayout.CENTER);
        body.add(columns, BorderLayout.CENTER);

        root.add(body, BorderLayout.CENTER);
        root.add(createStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);

        installLogRedirect();
        installWindowBehaviour();

        // 每 300 毫秒刷一次界面：跑的时候重画台账、更新计数；停下来就只更新一次
        Timer timer = new Timer(300, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (running && ledger != null) {
                    // 清单可能是在开跑之后才铺出来的（引擎拿到本地数据来源文件列表才知道有几只），
                    // 行数变了就重建一次，否则表格会一直是空的
                    if (tableModel.allSize() != ledger.total()) {
                        tableModel.setItems(ledger.items());
                    } else {
                        tableModel.refresh();
                    }
                }
                updateProgressUi();
            }
        });
        timer.start();

        selectTask(UpdateTask.POST_DOWNLOAD);
        loadInterruptedBanner();
        refreshStatusBar();
        startExternalIpRefresh();
        if (interrupted != null && interrupted.info.task != null) {
            // 打开就先把上次的台账铺出来：用户第一眼看到的是「哪些做完了、哪些没有」，
            // 而不是一片空白
            selectTask(interrupted.info.task);
            showRunFile(interrupted.info.dir, interrupted.info);
        }
        refreshHistoryPanel();
        refreshNavSubtitles();
    }

    // ================================================================== 顶部

            private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(updater.ui.UpdaterInfo.NAME_CN);
        name.setFont(UpdaterTheme.bold(15f));
        JLabel caption = new JLabel(updater.ui.UpdaterInfo.TAGLINE);
        caption.setFont(UpdaterTheme.regular(11f));
        caption.setForeground(UpdaterTheme.MUTED);
        titles.add(name);
        titles.add(caption);
        header.add(titles, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(UpdaterKit.button("路径设置", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new PathSettingsDialog(UpdaterFrame.this, new Runnable() {
                    @Override
                    public void run() {
                        refreshStatusBar();
                    }
                }).setVisible(true);
            }
        }));
        right.add(UpdaterKit.button("打开记录文件夹", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openLedgerFolder();
                refreshHistoryPanel();
            }
        }));
        header.add(right, BorderLayout.EAST);

        externalIpLabel = new JLabel("外网 IP 获取中…");
        externalIpLabel.setFont(SMALL);
        externalIpLabel.setForeground(UpdaterTheme.MUTED);
        externalIpLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        externalIpLabel.setToolTipText("这台机器现在走出去的外网地址；路由器重拨换 IP 以后跟着变，"
                + "不用另开网页去查");
        header.add(externalIpLabel, BorderLayout.CENTER);
        return header;
    }

    /**
     * 头顶那行外网 IP：取数被限速时重拨一次，换没换成看一眼就知道。
     *
     * <p>问的是公开的回显服务，一次几毫秒到几秒，全在后台线程；界面打开问一次，
     * 之后每分钟问一次，所以重拨完不需要重启程序。取不到就继续显示上一个地址。</p>
     */
    private void startExternalIpRefresh() {
        Timer timer = new Timer(60_000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshExternalIp();
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private void refreshExternalIp() {
        updater.net.PublicIp.refreshAsync(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String ip) {
                final String text = ip;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        showExternalIp(text);
                    }
                });
            }
        });
    }

    private void showExternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            externalIpLabel.setText("外网 IP 没取到");
            externalIpLabel.setForeground(UpdaterTheme.FAINT);
        } else {
            externalIpLabel.setText("外网 IP " + ip);
            externalIpLabel.setForeground(UpdaterTheme.TEXT);
        }
    }


    // ================================================================== 中断提示条

    /**
     * 顶部提示条：上次任务没跑完时出现。
     *
     * <p>这是整套改动的关键一环 —— 用户中断以后回到这个画面，第一眼要看到的不是
     * 一个空白的日志区，而是「上次跑到哪、还剩多少、要不要接着跑」。</p>
     */
    private JComponent createBanner() {
        banner = new JPanel(new BorderLayout(12, 0));
        banner.setBackground(UpdaterTheme.WARN_BG);
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UpdaterTheme.WARN_BORDER, 1),
                new EmptyBorder(8, 10, 8, 10)));
        banner.setVisible(false);

        bannerTextHolder = new JPanel(new BorderLayout());
        bannerTextHolder.setOpaque(false);
        banner.add(bannerTextHolder, BorderLayout.CENTER);

        bannerWrap = new JPanel(new BorderLayout());
        bannerWrap.setOpaque(false);
        bannerWrap.setBorder(new EmptyBorder(0, 0, 10, 0));
        bannerWrap.add(banner, BorderLayout.CENTER);
        bannerWrap.setVisible(false);
        return bannerWrap;
    }

    /** 提示条要连外层容器一起开关：里面那层只管自己 visible，外层不重排版就还是 0 高 */
    private void showBanner(boolean visible) {
        if (banner != null) {
            banner.setVisible(visible);
        }
        if (bannerWrap != null) {
            bannerWrap.setVisible(visible);
        }
    }

    private void loadInterruptedBanner() {
        interrupted = UpdateLedger.findInterrupted();
        if (interrupted == null || interrupted.info.task == null) {
            showBanner(false);
            return;
        }
        showResumeBanner(interrupted, "上次");
    }

    /**
     * 把「还剩多少、要不要接着跑」铺到顶部提示条上。
     *
     * @param prefix 「上次」= 开程序时发现的旧记录；「刚停下」= 本次手动停止
     */
    private void showResumeBanner(final UpdateLedger.Resume resume, String prefix) {
        if (resume == null || resume.info.task == null) {
            showBanner(false);
            return;
        }
        final int remaining = resume.remainingKeys().size();
        if (remaining <= 0) {
            showBanner(false);
            return;
        }
        // 计数从台账本身数：meta 里的数字可能停在开跑那一刻（进程被强杀时来不及刷）
        int done = 0;
        int failed = 0;
        int skipped = 0;
        for (UpdateLedger.Status status : resume.statuses.values()) {
            if (status == UpdateLedger.Status.DONE) {
                done++;
            } else if (status == UpdateLedger.Status.FAILED) {
                failed++;
            } else if (status == UpdateLedger.Status.SKIPPED) {
                skipped++;
            }
        }

        JLabel text = new JLabel("<html><div width='660'>" + prefix + "「"
                + resume.info.task.title + "」"
                + (resume.info.stopped ? "已停止" : "在 " + resume.info.timeText() + " 没跑完") + "："
                + "共 " + resume.keys.size() + " " + resume.info.task.unit
                + "，已完成 " + done + "，失败 " + failed
                + (skipped > 0 ? "，跳过 " + skipped : "")
                + "，还剩 <b>" + remaining + "</b> " + resume.info.task.unit
                + "没做。记录都在，可以接着跑。</div></html>");
        text.setFont(BODY);
        text.setForeground(UpdaterTheme.WARN_TEXT);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton resumeButton = UpdaterKit.primaryButton("接着跑剩下的 " + remaining + " " + resume.info.task.unit,
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        selectTask(resume.info.task);
                        startRun(resume.info.task, resume.info.params, resume, null);
                    }
                });
        JButton viewButton = UpdaterKit.button("看上次记录", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectTask(resume.info.task);
                showRunFile(resume.info.dir, resume.info);
            }
        });
        JButton dismiss = UpdaterKit.button("忽略", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showBanner(false);
            }
        });
        buttons.add(resumeButton);
        buttons.add(viewButton);
        buttons.add(dismiss);

        banner.removeAll();
        bannerTextHolder = new JPanel(new BorderLayout());
        bannerTextHolder.setOpaque(false);
        bannerTextHolder.add(text, BorderLayout.CENTER);
        banner.add(bannerTextHolder, BorderLayout.CENTER);
        banner.add(buttons, BorderLayout.EAST);
        banner.revalidate();
        showBanner(true);
    }

    // ================================================================== 左栏

    /**
     * 左栏按<b>数据类别</b>分组，每组标题上挂一个自己的「查缺补漏」。
     *
     * <p>查缺补漏不再是一个跟数据无关的独立任务：股价数据只查日线，
     * 财报与股东按报告期逐季对账，其他数据核名称库。三个检查各自有台账，
     * 但由组上的按钮切过去，不占组内那排任务按钮。</p>
     */
    private JComponent createLeftColumn() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);

        taskButtonGroup = new ButtonGroup();
        int index = 0;
        for (final UpdateTask.Group group : UpdateTask.Group.values()) {
            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            for (final UpdateTask task : UpdateTask.values()) {
                if (!task.navVisible || task.group != group) {
                    continue;
                }
                index++;
                final TaskNavButton button = new TaskNavButton(task, index);
                button.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        selectTask(task);
                    }
                });
                taskButtonGroup.add(button);
                taskButtons.put(task, button);
                body.add(button);
                body.add(Box.createVerticalStrut(4));
            }
            body.add(Box.createVerticalGlue());

            JComponent right = null;
            if (group.checkTask() != null) {
                JButton check = UpdaterKit.button("查缺补漏", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (!running) {
                            selectTask(group.checkTask());
                        }
                    }
                });
                check.setFont(SMALL);
                check.setToolTipText("只检查「" + group.title + "」这一类数据：" + group.caption);
                checkButtons.put(group, check);
                right = check;
            }

            JPanel card = UpdaterKit.compactSection(group.title, right, body);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            // BoxLayout 会把多余高度摊给能长大的组件；每个分区钉死自己的首选高度
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            column.add(card);
            column.add(Box.createVerticalStrut(10));
        }

        historyPanel = new JPanel();
        historyPanel.setLayout(new BoxLayout(historyPanel, BoxLayout.Y_AXIS));
        historyPanel.setOpaque(false);
        JPanel historyCard = UpdaterKit.compactSection("最近任务",
                UpdaterKit.hint("点一条看当时的明细"), wrapScroll(historyPanel));
        historyCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        column.add(historyCard);
        column.add(Box.createVerticalGlue());

        // 三组 + 历史已经高过一屏，整个左栏可滚；不要把任一组的任务挤没了
        JScrollPane scroll = new JScrollPane(column);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(248, 400));
        return scroll;
    }

    /** 组标题上的按钮：当前看哪一组的体检，就把那一组标成主按钮 */
    private void highlightCheckButtons(UpdateTask task) {
        for (Map.Entry<UpdateTask.Group, JButton> entry : checkButtons.entrySet()) {
            JButton button = entry.getValue();
            boolean active = task.isCheck() && task.group == entry.getKey();
            if (active) {
                UpdaterKit.stylePrimaryButton(button);
            } else {
                UpdaterKit.styleButton(button);
            }
            button.setFont(SMALL);
        }
    }

    /** 左栏历史列表：最近 8 次任务，点一条就把当时的台账铺到右边 */
    private void refreshHistoryPanel() {
        if (historyPanel == null) {
            return;
        }
        historyPanel.removeAll();
        List<UpdateLedger.RunInfo> runs = UpdateLedger.recentRuns(8);
        if (runs.isEmpty()) {
            JLabel empty = UpdaterKit.hint("还没有运行记录");
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            historyPanel.add(empty);
        }
        for (final UpdateLedger.RunInfo info : runs) {
            JButton item = new JButton();
            item.setLayout(new BorderLayout(0, 1));
            item.setOpaque(false);
            item.setContentAreaFilled(false);
            item.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UpdaterTheme.RULE),
                    new EmptyBorder(5, 6, 5, 6)));
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.setHorizontalAlignment(SwingConstants.LEFT);

            String title = info.task == null ? info.name : info.task.title;
            JLabel first = new JLabel(title);
            first.setFont(UpdaterTheme.regular(12f));
            first.setForeground(UpdaterTheme.TEXT);
            JLabel second = new JLabel(info.timeText() + " · " + info.countText()
                    + (info.finished ? "" : " · 未跑完"));
            second.setFont(SMALL);
            second.setForeground(info.finished ? UpdaterTheme.MUTED
                    : UpdaterTheme.WARN_TEXT);
            item.add(first, BorderLayout.NORTH);
            item.add(second, BorderLayout.CENTER);
            item.setToolTipText(info.dir.getAbsolutePath());
            item.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (running) {
                        return;
                    }
                    if (info.task != null) {
                        selectTask(info.task);
                    }
                    showRunFile(info.dir, info);
                }
            });
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            historyPanel.add(item);
        }
        historyPanel.revalidate();
        historyPanel.repaint();
        refreshNavSubtitles();
    }

    /** 左侧每个任务下面那行小字：最近一次跑成什么样 */
    private void refreshNavSubtitles() {
        Map<UpdateTask, UpdateLedger.RunInfo> latest = new LinkedHashMap<UpdateTask, UpdateLedger.RunInfo>();
        for (UpdateLedger.RunInfo info : UpdateLedger.recentRuns(60)) {
            if (info.task != null && !latest.containsKey(info.task)) {
                latest.put(info.task, info);
            }
        }
        for (UpdateTask task : UpdateTask.values()) {
            TaskNavButton button = taskButtons.get(task);
            if (button == null) {
                continue;
            }
            UpdateLedger.RunInfo info = latest.get(task);
            if (info == null) {
                button.setSubtitle("还没跑过");
            } else {
                button.setSubtitle(info.timeText() + " · " + info.countText()
                        + (info.finished ? "" : " · 未跑完"));
            }
        }
    }

    private JScrollPane wrapScroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    /** 左边一排任务按钮，两行文字：任务名 + 上次跑的结果；选中的那条填个浅灰底。 */
    private static final class TaskNavButton extends JToggleButton {

        private final JLabel titleLabel;
        private final JLabel subLabel;
        private boolean hover;

        TaskNavButton(UpdateTask task, int index) {
            setLayout(new BorderLayout(0, 2));
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorder(new EmptyBorder(6, 16, 6, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            titleLabel = new JLabel(task.title);
            titleLabel.setFont(TASK_TITLE_FONT);
            titleLabel.setForeground(UpdaterTheme.INK);
            subLabel = new JLabel(" ");
            subLabel.setFont(SMALL);
            subLabel.setForeground(UpdaterTheme.FAINT);
            add(titleLabel, BorderLayout.NORTH);
            add(subLabel, BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setPreferredSize(new Dimension(240, 46));
        }

        void setSubtitle(String text) {
            String next = text == null || text.isEmpty() ? " " : text;
            if (!next.equals(subLabel.getText())) {
                subLabel.setText(next);
            }
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            titleLabel.setFont(selected ? UpdaterTheme.bold(12f) : TASK_TITLE_FONT);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected()) {
                    g2.setColor(UpdaterTheme.ACCENT_SOFT);
                    g2.fillRoundRect(2, 1, getWidth() - 4, getHeight() - 2, 8, 8);
                    g2.setColor(UpdaterTheme.ACCENT);
                    g2.fillRoundRect(2, 8, 3, getHeight() - 16, 3, 3);
                } else if (hover) {
                    g2.setColor(UpdaterTheme.RAISED);
                    g2.fillRoundRect(2, 1, getWidth() - 4, getHeight() - 2, 8, 8);
                }
            } finally {
                g2.dispose();
            }
        }

    }

    // ================================================================== 右侧工作区

    private JComponent createWorkArea() {
        JPanel work = new JPanel(new BorderLayout(0, 12));
        work.setOpaque(false);
        work.add(createRunHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(createLedgerCard(), BorderLayout.CENTER);
        center.add(createLogCard(), BorderLayout.SOUTH);
        work.add(center, BorderLayout.CENTER);
        return work;
    }

    private JComponent createRunHeader() {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setOpaque(true);
        card.setBackground(UpdaterTheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                UpdaterKit.cardBorder(), new EmptyBorder(2, 2, 2, 2)));

        // 第一行：任务名 + 参数 + 主按钮
        JPanel first = new JPanel(new BorderLayout(12, 0));
        first.setOpaque(false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        taskTitleLabel = new JLabel();
        taskTitleLabel.setFont(UpdaterTheme.bold(15f));
        taskTitleLabel.setForeground(UpdaterTheme.TEXT_STRONG);
        taskSubtitleLabel = new JLabel();
        taskSubtitleLabel.setFont(SMALL);
        taskSubtitleLabel.setForeground(UpdaterTheme.MUTED);
        left.add(taskTitleLabel);
        left.add(Box.createVerticalStrut(2));
        left.add(taskSubtitleLabel);
        left.setAlignmentX(Component.LEFT_ALIGNMENT);

        paramZone = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, 8, 0));
        paramZone.setOpaque(false);

        JPanel leftColumn = new JPanel(new BorderLayout(0, 6));
        leftColumn.setOpaque(false);
        leftColumn.add(left, BorderLayout.NORTH);
        leftColumn.add(paramZone, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        retryButton = UpdaterKit.button("重试失败", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                retryFailed();
            }
        });
        retryButton.setEnabled(false);
        exportButton = UpdaterKit.button("导出清单", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                exportFailures();
            }
        });
        exportButton.setEnabled(false);
        stopButton = UpdaterKit.button("停止", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                askStop();
            }
        });
        stopButton.setEnabled(false);
        startButton = UpdaterKit.primaryButton("开始执行", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startSelectedTask();
            }
        });
        buttons.add(retryButton);
        buttons.add(exportButton);
        buttons.add(stopButton);
        buttons.add(startButton);

        first.add(leftColumn, BorderLayout.CENTER);
        first.add(buttons, BorderLayout.EAST);

        // 第二行：进度条 + 计数
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(0, 8));
        progressBar.setBorderPainted(false);
        progressBar.setForeground(UpdaterTheme.ACCENT);
        progressBar.setBackground(UpdaterTheme.RAISED);
        progressBar.setForeground(UpdaterTheme.SIGNAL);
        progressBar.setStringPainted(false);

        JPanel progressRow = new JPanel(new BorderLayout(12, 0));
        progressRow.setOpaque(false);
        countsLabel = new JLabel(" ");
        countsLabel.setFont(UpdaterTheme.regular(12f));
        countsLabel.setForeground(UpdaterTheme.TEXT);
        currentLabel = new JLabel(" ");
        currentLabel.setFont(SMALL);
        currentLabel.setForeground(UpdaterTheme.MUTED);
        resultLabel = new JLabel(" ");
        resultLabel.setFont(SMALL);
        resultLabel.setForeground(UpdaterTheme.MUTED);

        JPanel countStack = new JPanel();
        countStack.setOpaque(false);
        countStack.setLayout(new BoxLayout(countStack, BoxLayout.Y_AXIS));
        countStack.add(countsLabel);
        countStack.add(currentLabel);

        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(countStack, BorderLayout.EAST);

        JPanel second = new JPanel(new BorderLayout(0, 6));
        second.setOpaque(false);
        second.add(progressRow, BorderLayout.NORTH);
        second.add(resultLabel, BorderLayout.CENTER);

        // 参数行下面单独一块「数据来源」：当前任务能走哪几条线路，全列出来
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(first, BorderLayout.NORTH);
        sourceZone = new JPanel();
        sourceZone.setLayout(new BoxLayout(sourceZone, BoxLayout.Y_AXIS));
        sourceZone.setOpaque(false);
        top.add(sourceZone, BorderLayout.CENTER);

        card.add(top, BorderLayout.NORTH);
        card.add(second, BorderLayout.CENTER);
        return card;
    }

    private JComponent createLedgerCard() {
        // 顶部：筛选胶囊 + 搜索
        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterRow.setOpaque(false);

        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chipRow.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (final LedgerTableModel.Filter filter : LedgerTableModel.Filter.values()) {
            final JToggleButton chip = new JToggleButton(filter.label);
            chip.setFont(BODY);
            chip.setFocusPainted(false);
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.setBorder(new EmptyBorder(4, 10, 4, 10));
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (chip.isSelected()) {
                        tableModel.setFilter(filter);
                        styleChip(chip, true);
                    } else {
                        styleChip(chip, false);
                    }
                }
            });
            group.add(chip);
            chips.put(filter, chip);
            chipRow.add(chip);
            if (filter == LedgerTableModel.Filter.ALL) {
                chip.setSelected(true);
                styleChip(chip, true);
            } else {
                styleChip(chip, false);
            }
        }

        searchField = UpdaterKit.ghostField("筛选代码，如 600 或 300750", 16);
        searchField.setPreferredSize(new Dimension(200, 28));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                tableModel.setSearch(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                tableModel.setSearch(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                tableModel.setSearch(searchField.getText());
            }
        });

        filterRow.add(chipRow, BorderLayout.WEST);
        filterRow.add(searchField, BorderLayout.EAST);

        // 表格
        table = new JTable(tableModel);
        UpdaterKit.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new ItemRenderer());
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(0).setPreferredWidth(110);
        columns.getColumn(1).setPreferredWidth(80);
        columns.getColumn(2).setPreferredWidth(520);
        columns.getColumn(3).setPreferredWidth(80);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    showSelectedItemDetail();
                }
            }
        });

        emptyTableLabel = new JLabel(
                "<html><div width='420'>这次还没跑过。<br>"
                        + "点「开始执行」后这里会一条条列出来；中途关掉也不会丢，下次能接着跑。</div></html>",
                SwingConstants.CENTER);
        emptyTableLabel.setFont(BODY);
        emptyTableLabel.setForeground(UpdaterTheme.MUTED);

        tableCards = new CardLayout();
        tableHolder = new JPanel(tableCards);
        tableHolder.setOpaque(false);
        tableHolder.add(UpdaterKit.tableScroll(table), "table");
        tableHolder.add(emptyTableLabel, "empty");
        tableCards.show(tableHolder, "empty");

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(filterRow, BorderLayout.NORTH);
        body.add(tableHolder, BorderLayout.CENTER);

        JPanel card = UpdaterKit.compactSection("这次跑的记录", UpdaterKit.hint("双击一行看完整原因"), body);
        card.putClientProperty("ledger.card", Boolean.TRUE);
        return card;
    }

        private void styleChip(JToggleButton chip, boolean selected) {
        chip.setFont(selected ? UpdaterTheme.bold(11f) : UpdaterTheme.regular(11f));
        chip.repaint();
    }


    private JComponent createLogCard() {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(6, 0, 4, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UpdaterKit.sectionTitle("日志"), BorderLayout.WEST);

        logToggle = UpdaterKit.button("收起", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean visible = !logScroll.isVisible();
                logScroll.setVisible(visible);
                logToggle.setText(visible ? "收起" : "展开");
                logToggle.getParent().getParent().revalidate();
            }
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(logToggle);
        header.add(right, BorderLayout.EAST);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(UpdaterTheme.regular(12f));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(false);
        logArea.setBackground(LOG_BG);
        logArea.setForeground(UpdaterTheme.INK_SOFT);
        logArea.setBorder(new EmptyBorder(4, 6, 4, 6));
        logScroll = new JScrollPane(logArea);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScroll.setPreferredSize(new Dimension(0, 132));

        card.add(header, BorderLayout.NORTH);
        card.add(logScroll, BorderLayout.CENTER);
        return card;
    }

    private JComponent createStatusBar() {
        JPanel bar = UpdaterKit.statusBar();
        statusDataDir = new JLabel(" ");
        statusDataDir.setFont(UpdaterTheme.regular(11f));
        statusDataDir.setForeground(UpdaterTheme.MUTED);
        statusLedgerDir = new JLabel(" ");
        statusLedgerDir.setFont(UpdaterTheme.regular(11f));
        statusLedgerDir.setForeground(UpdaterTheme.MUTED);
        bar.add(statusDataDir, BorderLayout.WEST);
        bar.add(statusLedgerDir, BorderLayout.EAST);
        return bar;
    }

    private void refreshStatusBar() {
        String root = UpdaterDataDir.root();
        String stockDir = updater.mount.DataRoot.stockDirOf(root);
        int count = updater.mount.DataRoot.countStockDbs(new File(stockDir));
        statusDataDir.setText("数据文件夹: " + root + "（" + UpdaterDataDir.source().label()
                + "，" + count + " 个库）");
        statusLedgerDir.setText("记录目录: " + UpdateLedger.runsRoot().getAbsolutePath());
    }

    // ================================================================== 选中任务

    private void selectTask(UpdateTask task) {
        if (running) {
            return;
        }
        selectedTask = task;
        TaskNavButton button = taskButtons.get(task);
        if (button != null) {
            button.setSelected(true);
        } else if (taskButtonGroup != null) {
            // 三个查缺补漏不在任务按钮列表里：此时左栏没有任何任务被选中，
            // 由组标题上的按钮高亮来表示「正在看这一组的体检」
            taskButtonGroup.clearSelection();
        }
        highlightCheckButtons(task);
        taskTitleLabel.setText(task.title);
        taskSubtitleLabel.setText(task.subtitle);
        if (ledger == null || ledger.task() != task) {
            resultLabel.setForeground(UpdaterTheme.MUTED);
            resultLabel.setText("参数确认后点右上「开始执行」；跑到一半可以随时停，停了的能接着跑");
        }
        buildParamZone(task);
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        startButton.setText("开始执行");
        if (ledger != null && ledger.task() == task) {
            updateProgressUi();
        } else {
            showEmptyTable();
            countsLabel.setText(" ");
            currentLabel.setText(" ");
            resultLabel.setForeground(UpdaterTheme.MUTED);
            resultLabel.setText("参数确认后点右上「开始执行」；跑到一半可以随时停，停了的能接着跑");
            progressBar.setValue(0);
            progressBar.setStringPainted(false);
            progressBar.setVisible(false);
            retryButton.setEnabled(false);
            retryButton.setText(task.isCheck() ? "修复缺失" : "重试失败");
            exportButton.setEnabled(false);
        }
    }

    private void showEmptyTable() {
        tableModel.setItems(new ArrayList<UpdateLedger.Item>());
        tableCards.show(tableHolder, "empty");
    }

    private void showTable() {
        tableCards.show(tableHolder, "table");
    }

    /** 任务参数与数据来源：全部内联，不再弹框问 */
    private void buildParamZone(UpdateTask task) {
        paramZone.removeAll();
        buildParamZoneBody(task);
        paramZone.revalidate();
        paramZone.repaint();
        buildSourceZone(task);
    }

    /**
     * 把这个任务的默认线路和备用线路都摆出来，附一句各自取的是什么。
     * 「自动」是默认档；手选只对本次执行生效，切任务就回到自动。
     */
    private void buildSourceZone(UpdateTask task) {
        if (sourceZone == null) {
            return;
        }
        sourceZone.removeAll();
        selectedSourceId = UpdateSources.AUTO;
        sourceGroup = new ButtonGroup();

        JPanel box = new JPanel(new BorderLayout(8, 6));
        box.setOpaque(true);
        box.setBackground(UpdaterTheme.ACCENT_SOFT);
        box.setBorder(BorderFactory.createCompoundBorder(
                UpdaterKit.cardBorder(), new EmptyBorder(7, 10, 7, 10)));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JLabel title = new JLabel("数据来源");
        title.setFont(UpdaterTheme.bold(11f));
        title.setForeground(UpdaterTheme.ACCENT);
        head.add(title, BorderLayout.WEST);
        head.add(UpdaterKit.hint(allSourceNames()), BorderLayout.CENTER);
        box.add(head, BorderLayout.NORTH);

        JPanel lines = new JPanel();
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.setOpaque(false);
        lines.add(sourceRow(UpdateSources.AUTO, UpdateSources.AUTO_LABEL,
                UpdateSources.AUTO_NOTE, "默认", true));
        List<UpdateSources.Source> sources = UpdateSources.of(task);
        if (sources.isEmpty()) {
            JLabel none = UpdaterKit.hint("这个任务用固定来源，没有可切换的线路");
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            lines.add(none);
        } else {
            for (int i = 0; i < sources.size(); i++) {
                UpdateSources.Source source = sources.get(i);
                lines.add(sourceRow(source.id, UpdateSources.labelOf(source),
                        UpdateSources.noteOf(source),
                        i == 0 ? "默认线路" : "备用 " + i, false));
            }
        }
        box.add(lines, BorderLayout.CENTER);
        sourceZone.add(box);
        sourceZone.revalidate();
        sourceZone.repaint();
    }

    private JComponent sourceRow(final String id, String label, String note,
                                 String tag, boolean selected) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JRadioButton radio = new JRadioButton(label + " · " + tag);
        radio.setFont(BODY);
        radio.setOpaque(false);
        radio.setFocusPainted(false);
        radio.setSelected(selected);
        radio.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedSourceId = id;
            }
        });
        sourceGroup.add(radio);
        row.add(radio, BorderLayout.WEST);
        row.add(UpdaterKit.hint(note), BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Short.MAX_VALUE, 22));
        return row;
    }

    /** 全部线路一览：即便当前任务只用了其中几条，名字也不藏着 */
    private static String allSourceNames() {
        return "全部线路：" + UpdateSources.labelOf(UpdateSources.TDX) + " / "
                + UpdateSources.labelOf(UpdateSources.TENCENT) + " / "
                + UpdateSources.labelOf(UpdateSources.SINA) + " / "
                + UpdateSources.labelOf(UpdateSources.EASTMONEY) + " / "
                + UpdateSources.labelOf(UpdateSources.DZH);
    }

    private void buildParamZoneBody(UpdateTask task) {
        switch (task) {
            case POST_DOWNLOAD: {
                paramZone.add(label("更新天数"));
                daysSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 1000, 1));
                daysSpinner.setPreferredSize(new Dimension(70, 26));
                paramZone.add(daysSpinner);
                backupCheck = new JCheckBox("先备份数据库");
                backupCheck.setSelected(true);
                backupCheck.setOpaque(false);
                backupCheck.setFont(BODY);
                backupCheck.setToolTipText("整目录打包到「数据备份文件夹」（路径设置里配），库多时要几十秒");
                paramZone.add(backupCheck);
                // 日线写完紧接着补 5 分钟：分钟线是另一条链路，单独跑一趟天天都会忘
                minCheck = new JCheckBox("顺带补 5 分钟线");
                minCheck.setSelected(true);
                minCheck.setOpaque(false);
                minCheck.setFont(BODY);
                minCheck.setToolTipText("写完全市场日线后，直接从本地数据来源的分钟文件补 5 分钟线（不联网，只补不删），"
                        + "接着同步今日公告；第一次补要几分钟，之后每天只补当天那几根");
                paramZone.add(minCheck);
                // 老画面的第三个来源（本地 CSV）收在这里，不再靠一层层弹框问
                paramZone.add(UpdaterKit.button("从本地 CSV 加载…", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (running) {
                            return;
                        }
                        updater.main.load.LocalDataLoader.showLoadDialog(UpdaterFrame.this);
                    }
                }));
                break;
            }
            case LATEST_PRICE: {
                paramZone.add(hintLabel("按本地库 / 名称库 / 本地日线文件自动识别代码清单，没有库会顺手建库；"
                        + "写完会问一句要不要连 5 分钟线一起补"));
                break;
            }
            case HISTORY: {
                paramZone.add(hintLabel("只补本地没有的日期，本地没有库的股票跳过（不会顺手建库）"));
                break;
            }
            case INTEGRITY: {
                paramZone.add(label("检查范围"));
                rangeCombo = new JComboBox<String>(INTEGRITY_RANGES);
                rangeCombo.setFont(BODY);
                rangeCombo.setPreferredSize(new Dimension(120, 26));
                rangeCombo.setSelectedIndex(0);
                paramZone.add(rangeCombo);
                checkMin5Box = new JCheckBox("检查 5 分钟数据");
                checkMin5Box.setSelected(true);
                checkMin5Box.setOpaque(false);
                checkMin5Box.setFont(BODY);
                checkMin5Box.setToolTipText("每只都核 T_D_Min5：有没有表、末根落后、近 10 个交易日有没有整天缺；"
                        + "本地没有分钟文件的股票也一起核");
                paramZone.add(checkMin5Box);
                onlineMin5Box = new JCheckBox("缺的用在线线路补");
                onlineMin5Box.setSelected(true);
                onlineMin5Box.setOpaque(false);
                onlineMin5Box.setFont(BODY);
                onlineMin5Box.setToolTipText("本地数据来源没有分钟文件时，修复阶段改走在线逐只取 5 分钟线；"
                        + "在线这一档会在东方财富 / 新浪 / 腾讯之间逐只换源；关掉就只补有本地文件的那些");
                paramZone.add(onlineMin5Box);
                paramZone.add(hintLabel(
                        "逐只对账日线与 5 分钟线：缺库 / 缺日期 / 分钟线落后都会标红，"
                                + "跑完点「修复缺失」按台账补齐（缺的 5 分钟线能一起补回来）"));
                break;
            }
            case FINANCE_INTEGRITY: {
                paramZone.add(label("检查范围"));
                financeRangeCombo = new JComboBox<String>(FINANCE_CHECK_RANGES);
                financeRangeCombo.setFont(BODY);
                financeRangeCombo.setPreferredSize(new Dimension(120, 26));
                financeRangeCombo.setSelectedIndex(1);
                paramZone.add(financeRangeCombo);
                // 耗时写在可见文字里：禁用/未悬停时 tooltip 根本不弹
                refreshHoldersCheck = new JCheckBox("修复时刷新股东（只补缺的那几只，秒级）");
                refreshHoldersCheck.setOpaque(false);
                refreshHoldersCheck.setFont(BODY);
                refreshHoldersCheck.setSelected(true);
                paramZone.add(refreshHoldersCheck);
                paramZone.add(hintLabel("按应披露季度逐季核财报 + 十大流通股东；标红的季度点「修复缺失」，"
                        + "只补差的那几只，不动整季"));
                break;
            }
            case NAME_INTEGRITY: {
                paramZone.add(hintLabel(
                        "按本地个股库清单逐只核名称库；修复会重新抓一遍全市场名称"));
                break;
            }
            case MIN_DATA: {
                paramZone.add(hintLabel("只入 5 分钟线，只补不删；盘后数据下载、补最新价格都可以顺带补，"
                        + "这里用于单独补跑。本地文件这条路走不通时会自动换一条"));
                break;
            }
            case DIVIDEND: {
                paramZone.add(hintLabel("读取本地除权除息数据，按报告期修正本地除权表"));
                break;
            }
            case INDEX_DATA: {
                paramZone.add(hintLabel("取 " + updater.fetchdata.IndexCodes.all().size()
                        + " 个常用指数（上证 / 深证成指 / 创业板指 / 沪深300 / 中证500 / 中证1000 / 科创50 / 北证50）"
                        + "的全部日线；本地没有库会新建，已有的只补缺的日期。"
                        + "指数不参与选股，只在脚本里用 IDX(\"sh000001\", C) 引用"));
                break;
            }
            case FINANCE: {
                paramZone.add(label("起始季度"));
                financeStart = new JTextField(7);
                financeStart.setFont(BODY);
                financeStart.setToolTipText("格式 yyyyMM，如 202603；两个季度都留空 = 只更新服务器最新一期");
                paramZone.add(financeStart);
                paramZone.add(label("结束季度"));
                financeEnd = new JTextField(7);
                financeEnd.setFont(BODY);
                financeEnd.setToolTipText("留空 = 只下起始季度；填了就下这段区间内每一季");
                paramZone.add(financeEnd);
                break;
            }
            case HOLDER_SYNC: {
                paramZone.add(label("同步范围"));
                holderRangeCombo = new JComboBox<String>(HOLDER_RANGES);
                holderRangeCombo.setFont(BODY);
                holderRangeCombo.setPreferredSize(new Dimension(130, 26));
                holderRangeCombo.setSelectedIndex(0);
                paramZone.add(holderRangeCombo);
                holderSkipCheck = new JCheckBox("跳过本地已有的季度");
                holderSkipCheck.setOpaque(false);
                holderSkipCheck.setFont(BODY);
                holderSkipCheck.setSelected(true);
                holderSkipCheck.setToolTipText("取消勾选会覆盖重拉选中的季度；一季约 5 万行、1 分钟");
                paramZone.add(holderSkipCheck);
                paramZone.add(hintLabel("东财「十大流通股东」，只取流通 A 股；一季约 5 万行"));
                break;
            }
            case RISK_EVENTS: {
                paramZone.add(hintLabel("读本地 f10.db 里判过情绪的利空公告；先跑过盘后下载才有数据"));
                break;
            }
            case RISK_LIFTS: {
                paramZone.add(hintLabel("取前 90 天到未来 12 个月，一年区间约 1500 条；联网约 2 秒"));
                break;
            }
            case RISK_VALUATION: {
                paramZone.add(hintLabel("读 5300 个个股库 + 财报库；不联网，首次约 40 秒"));
                break;
            }
            case PIT_NOTICE: {
                paramZone.add(hintLabel("取最近三年实际披露日；约 190 页，联网约 1 分钟"));
                break;
            }
            case STOCK_NAME: {
                paramZone.add(hintLabel("写独立名称库 stock_name.db，下次启动生效"));
                break;
            }
            case ANNOUNCEMENT: {
                paramZone.add(label("回溯天数"));
                announceDaysSpinner = new JSpinner(new SpinnerNumberModel(7, 1, 60, 1));
                announceDaysSpinner.setPreferredSize(new Dimension(70, 26));
                paramZone.add(announceDaysSpinner);
                paramZone.add(hintLabel("单独跑一次全市场公告入库；盘后下载也会顺带跑，这里用于补跑或回溯更久"));
                break;
            }
            case SHARE_CAPITAL: {
                paramZone.add(hintLabel("从本地财报字段回填各报告期的总股本、流通 A 股与股东人数；"
                        + "不联网，跑完 K 线图的换手率就能算"));
                break;
            }
            default:
                break;
        }
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(BODY);
        label.setForeground(UpdaterTheme.TEXT);
        return label;
    }

    /**
     * 会换行的 {@link FlowLayout}。
     *
     * <p>参数区原来用普通 FlowLayout：它算首选高度时<b>只按一行</b>算，
     * 一旦控件放不下（多一个控件、或者用户把窗口拉窄、系统 DPI 调大），
     * 后面那些控件就被挤到第二行、而分区高度没跟着长 —— 看起来像"按钮消失了"。
     * 这里按真实可用宽度先算出要几行，再给出正确的高度。</p>
     */
    private static final class WrapFlowLayout extends FlowLayout {

        WrapFlowLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                int width = target.getWidth();
                return width <= 0 ? super.preferredLayoutSize(target)
                        : wrappedSize(target, width, true);
            }
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                int width = target.getWidth();
                return width <= 0 ? super.minimumLayoutSize(target)
                        : wrappedSize(target, width, false);
            }
        }

        private Dimension wrappedSize(Container target, int width, boolean preferred) {
            Insets insets = target.getInsets();
            int usable = width - insets.left - insets.right - getHgap() * 2;
            int rowWidth = 0;
            int rowHeight = 0;
            int total = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component child = target.getComponent(i);
                if (!child.isVisible()) {
                    continue;
                }
                Dimension size = preferred ? child.getPreferredSize() : child.getMinimumSize();
                if (rowWidth > 0 && rowWidth + size.width > usable) {
                    total += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += size.width + getHgap();
                rowHeight = Math.max(rowHeight, size.height);
            }
            total += rowHeight;
            return new Dimension(width,
                    total + insets.top + insets.bottom + getVgap() * 2);
        }
    }

    private JLabel hintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(SMALL);
        label.setForeground(UpdaterTheme.MUTED);
        return label;
    }

    // ================================================================== 启动 / 结束

    private void startSelectedTask() {
        if (running) {
            return;
        }
        UpdateTask task = selectedTask;
        Map<String, String> params = collectParams(task);
        if (params == null) {
            return;
        }
        if (task == UpdateTask.FINANCE) {
            prepareFinanceThenRun(params);
            return;
        }
        startRun(task, params, null, null);
    }

    /** 读界面上的参数；校验不通过返回 null（已经提示过用户） */
    private Map<String, String> collectParams(UpdateTask task) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        // 线路是公共参数：从画面上的「数据来源」面板读，默认自动，也可以点选具体线路。
        // 只对本次执行生效，不写配置、不写偏好；切任务时面板会重置回自动。
        params.put("source", selectedSourceId == null ? UpdateSources.AUTO : selectedSourceId);
        params.put("checkMin5", Boolean.toString(checkMin5Box == null || checkMin5Box.isSelected()));
        params.put("onlineMin5", Boolean.toString(onlineMin5Box == null || onlineMin5Box.isSelected()));
        switch (task) {
            case POST_DOWNLOAD: {
                int days = 5;
                if (daysSpinner != null && daysSpinner.getValue() instanceof Number) {
                    days = ((Number) daysSpinner.getValue()).intValue();
                }
                params.put("days", Integer.toString(days));
                boolean wantBackup = backupCheck != null && backupCheck.isSelected();
                if (BackupDir.configured().isEmpty()) {
                    // 没设备份文件夹：下载前必须让用户知道「这次不会备份」，三选一
                    Object[] options = new Object[]{"继续", "设置", "取消"};
                    int pick = JOptionPane.showOptionDialog(this,
                            "还没有设置数据备份文件夹，本次不会备份数据库。\n\n"
                                    + "继续：不备份，直接更新数据\n"
                                    + "设置：先选备份文件夹，再继续更新\n"
                                    + "取消：这次先不更新",
                            "未设置数据备份文件夹", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                    if (pick == JOptionPane.CLOSED_OPTION || pick == 2) {
                        return null;
                    }
                    if (pick == 1) {
                        File chosen = chooseBackupDir();
                        if (chosen == null) {
                            return null;
                        }
                        try {
                            BackupDir.save(chosen.getAbsolutePath());
                            wantBackup = true;
                            if (backupCheck != null) {
                                backupCheck.setSelected(true);
                            }
                            refreshStatusBar();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this,
                                    "保存数据备份文件夹失败: " + ex.getMessage(),
                                    "数据备份文件夹", JOptionPane.ERROR_MESSAGE);
                            return null;
                        }
                    } else {
                        wantBackup = false;
                        if (backupCheck != null) {
                            backupCheck.setSelected(false);
                        }
                    }
                }
                params.put("backup", Boolean.toString(wantBackup));
                params.put("min5", Boolean.toString(minCheck == null || minCheck.isSelected()));
                break;
            }
            case DIVIDEND: {
                // source 已经在方法开头统一放进了 params
                break;
            }
            case INDEX_DATA: {
                // 没有参数：清单固定（IndexCodes），取全部历史
                break;
            }
            case ANNOUNCEMENT: {
                int days = 7;
                if (announceDaysSpinner != null
                        && announceDaysSpinner.getValue() instanceof Number) {
                    days = ((Number) announceDaysSpinner.getValue()).intValue();
                }
                params.put("days", Integer.toString(days));
                break;
            }
            case FINANCE: {
                String start = financeStart == null ? "" : financeStart.getText().trim();
                String end = financeEnd == null ? "" : financeEnd.getText().trim();
                params.put("start", start);
                params.put("end", end);
                break;
            }
            case HOLDER_SYNC: {
                int index = holderRangeCombo == null ? 0 : holderRangeCombo.getSelectedIndex();
                int count = HOLDER_COUNTS[Math.max(0, Math.min(HOLDER_COUNTS.length - 1, index))];
                params.put("count", Integer.toString(count));
                params.put("skipExisting", Boolean.toString(
                        holderSkipCheck == null || holderSkipCheck.isSelected()));
                break;
            }
            case INTEGRITY: {
                int index = rangeCombo == null ? 0 : rangeCombo.getSelectedIndex();
                integrityMinTime = integrityMinTimeOf(index);
                params.put("minTime", Long.toString(integrityMinTime));
                params.put("mode", "scan");
                break;
            }
            case FINANCE_INTEGRITY: {
                int index = financeRangeCombo == null ? 1 : financeRangeCombo.getSelectedIndex();
                financeCheckYears = financeCheckYearsOf(index);
                refreshHoldersOnRepair = refreshHoldersCheck == null
                        || refreshHoldersCheck.isSelected();
                params.put("years", Integer.toString(financeCheckYears));
                params.put("refreshHolders", Boolean.toString(refreshHoldersOnRepair));
                params.put("mode", "scan");
                break;
            }
            case NAME_INTEGRITY: {
                params.put("mode", "scan");
                break;
            }
            default:
                break;
        }
        return params;
    }

    /**
     * 跑一个任务。
     *
     * @param resume    续跑用：非空时按旧台账的清单重开一张，只跑没完成的
     * @param retryKeys 重试用：只跑这几只（非空时新台账只包含它们）
     */
    private void startRun(final UpdateTask task, final Map<String, String> params,
                          UpdateLedger.Resume resume, List<String> retryKeys) {
        if (running) {
            return;
        }
        if (!prepareDataDir()) {
            return;
        }
        if (task.needsTdx && UpdateSources.needsTdx(task, params.get("source"))
                && !ensureTdxDataDir(true)) {
            return;
        }
        // 除权这类任务本身不强制本地数据来源，但走本地线路就得先有目录
        if (!task.needsTdx && UpdateSources.needsTdx(task, params.get("source"))
                && !ensureTdxDataDir(false)) {
            return;
        }

        final List<String> onlyKeys;
        try {
            ledger = resume != null
                    ? UpdateLedger.reopen(resume)
                    : UpdateLedger.create(task, params);
            onlyKeys = resume != null ? resume.remainingKeys() : retryKeys;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "台账目录不可用，无法开始:\n" + ex.getMessage(),
                    "台账", JOptionPane.ERROR_MESSAGE);
            return;
        }

        cancel.set(false);
        running = true;
        runStartMillis = System.currentTimeMillis();
        afterFinish = null;
        // 三组体检的结论不能串到别组的结果行上
        lastIntegritySummary = null;
        lastFinanceSummary = null;
        lastNameSummary = null;
        runningRepair = false;
        tableModel.setItems(ledger.items());
        tableModel.setSearch("");
        if (searchField != null) {
            searchField.setText("");
        }
        selectFilter(LedgerTableModel.Filter.ALL);
        showTable();
        // 开始跑了，上次中断的提示条就该收起来（结论已经显示在下面的台账里）
        showBanner(false);
        progressBar.setVisible(true);
        setRunningUi(true);
        updateProgressUi();
        String retryNote = "";
        if (retryKeys != null) {
            // 查缺补漏的「重试」其实是补数据，日志里别写成「重试失败」让人以为是又查一遍
            retryNote = (task.isCheck() ? "（修复缺失的 " : "（重试失败的 ")
                    + retryKeys.size() + " " + task.unit + "）";
        }
        appendLog("== 开始: " + task.title
                + (resume != null ? "（续跑，只跑剩下的 " + (onlyKeys == null ? 0 : onlyKeys.size()) + " " + task.unit + "）" : "")
                + retryNote);
        resultLabel.setText("正在执行…");

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                // 本次执行专用的参数副本：备用线路只写进这个内存 Map，
                // 台账（startRun 里已按默认线路落盘）、配置、偏好文件都不碰。
                // 下一次 collectParams 会重新造一份，自然回到默认线路。
                final Map<String, String> attemptParams =
                        new LinkedHashMap<String, String>(params);
                try {
                    runBody(task, attemptParams, onlyKeys);
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    cause.printStackTrace();
                    if (canOfferBackup(task, attemptParams)) {
                        if (askSwitchToBackup()) {
                            failure = tryBackups(task, attemptParams, onlyKeys);
                        } else {
                            failure = errorMessage(cause);
                            appendLog("== 已放弃切换，本次按失败结束");
                        }
                    } else {
                        failure = errorMessage(cause);
                    }
                }
                final String message = failure;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        finishRun(message);
                    }
                });
            }
        }, "updater-run");
        // 关掉窗口就该能退出：不让后台线程把进程吊着
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 只有「默认线路失败、这个任务确实有备用线路、且不是用户主动停止」时才提示切换。
     *
     * <p>备用线路自身的失败不再弹窗，免得一次执行反复打断用户；参数校验那一类
     * 失败在 {@code collectParams} 阶段就已经拦下，不会走到这里。</p>
     */
    private boolean canOfferBackup(UpdateTask task, Map<String, String> attemptParams) {
        // 手选了具体线路也照样让切换兜底；tryBackups 会跳过刚试过的那一条
        return !cancel.get() && !UpdateSources.backups(task).isEmpty();
    }

    /**
     * 中性提示：只问「要不要换一条线路再试」，不出现任何线路名、接口名、主机名。
     *
     * <p>从后台线程用 invokeAndWait 切回 EDT 弹模态框；用户没回答，worker 就停在这里等，
     * 不会在后台偷偷继续跑。</p>
     */
    private boolean askSwitchToBackup() {
        final boolean[] agreed = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    Object[] options = new Object[]{"切换并重试", "暂不切换"};
                    int pick = JOptionPane.showOptionDialog(UpdaterFrame.this,
                            "数据读取失败，自动重试后仍无法连接。\n\n"
                                    + "是否切换到备用线路再试一次？\n\n"
                                    + "本次切换只在这一次执行生效，下次仍按画面上选的线路起步。",
                            "线路不可用", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                    agreed[0] = (pick == 0);
                }
            });
        } catch (Exception e) {
            agreed[0] = false;
        }
        return agreed[0];
    }

    /**
     * 依次试备用线路，成功即返回 null（让 finishRun 走正常完成）。
     *
     * <p>全程只改 {@code attemptParams} 这个内存副本：不写台账、不写配置、不写偏好。
     * 日志一律用中性措辞，不暴露具体线路。切换后的线路对本地目录的要求不会高于
     * 默认线路（startRun 已经按默认线路校验过），所以这里不必再打断用户问目录。</p>
     */
    private String tryBackups(UpdateTask task, Map<String, String> attemptParams,
                              List<String> onlyKeys) {
        List<UpdateSources.Source> backups = UpdateSources.backups(task);
        String current = UpdateSources.resolveId(task, attemptParams.get("source"));
        String lastFailure = null;
        int index = 0;
        for (UpdateSources.Source backup : backups) {
            if (cancel.get()) {
                return null;
            }
            if (backup.id.equals(current)) {
                // 这一条刚试过（可能是用户在画面上点选的），别原地再来一遍
                continue;
            }
            index++;
            attemptParams.put("source", backup.id);
            appendLog("== 已切换备用线路（第 " + index + " 条），重新尝试…");
            try {
                runBody(task, attemptParams, onlyKeys);
                appendLog("== 备用线路执行完成");
                return null;
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                cause.printStackTrace();
                lastFailure = errorMessage(cause);
                appendLog("== 该线路仍不可用"
                        + (index < backups.size() ? "，继续试下一条…" : ""));
            }
        }
        return lastFailure == null ? "备用线路均无法连接" : lastFailure;
    }

    /** 按任务分发到具体的执行体 */
    private void runBody(UpdateTask task, Map<String, String> params, List<String> onlyKeys)
            throws Exception {
        switch (task) {
            case POST_DOWNLOAD: {
                int days = intParam(params, "days", 5);
                boolean backup = "true".equalsIgnoreCase(params.get("backup"));
                if (isEastMoney(params)) {
                    // 东财来源也要能先备份：两个来源的行为保持一致，不然换了来源数据反而没兜底
                    if (backup) {
                        try {
                            MainDownLoadFromTdx.backupOnly(logSink());
                        } catch (Exception e) {
                            logSink().accept("备份失败（不影响下面的更新）: " + errorMessage(e));
                        }
                    }
                    EastMoneyDayFetcher.run(logSink(), ledger, cancel, onlyKeys, days, true);
                } else {
                    MainDownLoadFromTdx.run(days, backup, logSink(), ledger, cancel, onlyKeys);
                }
                // 日线写完之后是两条跟着走的事：先补 5 分钟线，再同步公告（今天发的都在这批里）。
                // 顺序不能反 —— 公告同步要弹模态框（走 afterFinish），一弹就把后面的步骤挡住了。
                if (!"false".equalsIgnoreCase(params.get("min5"))) {
                    // 盘后这一趟只读本地分钟文件、不联网：收盘后的 5 分钟必须是本地那份，
                    // 在线接口的窗口浅、量额口径也不一样，混进去只会让当天数据缺一截
                    syncMinutesAfterPrices(onlyKeys, true);
                }
                if (!cancel.get()) {
                    // 回溯 7 天而不是 1 天：周末 / 节假日发的公告（重组、业绩预告）也要进来，
                    // 少下几天再补时也不会漏。当天的公告自然包含在里面。
                    afterFinish = announcementSync(7);
                }
                break;
            }
            case LATEST_PRICE:
                LatestPriceUpdater.run(logSink(), ledger, cancel, onlyKeys, params.get("source"));
                // 日线写完之后问一句要不要连 5 分钟一起补：分钟线是另一条链路，
                // 不主动问的话，用户几乎不会为了当天那 48 根再单独点一次任务。
                // 顺序仍然是「先分钟、后公告」—— 公告同步要弹模态框，一弹就把后面的挡住了。
                if (!cancel.get() && askSyncMinutesAfterPrice()) {
                    syncMinutesAfterPrices(onlyKeys, false);
                }
                afterFinish = announcementSync(3);
                break;
            case HISTORY:
                if (isEastMoney(params)) {
                    // 东财一次返回全部历史，days<=0 表示不截取；本地没有库就建，跟本地数据来源那条一致
                    EastMoneyDayFetcher.run(logSink(), ledger, cancel, onlyKeys, 0, true);
                } else {
                    ReaderFromTdx.getInstance().importAllHistory(logSink(), ledger, cancel, onlyKeys);
                }
                break;
            case INTEGRITY: {
                long minTime = longParam(params, "minTime", 0);
                boolean fix = "fix".equalsIgnoreCase(params.get("mode"));
                boolean checkMin5 = !"false".equalsIgnoreCase(params.get("checkMin5"));
                boolean onlineMin5 = !"false".equalsIgnoreCase(params.get("onlineMin5"));
                runningRepair = fix;
                lastIntegritySummary = fix
                        ? DataIntegrityChecker.repair(logSink(), ledger, cancel, onlyKeys, minTime,
                                checkMin5, onlineMin5)
                        : DataIntegrityChecker.scan(logSink(), ledger, cancel, onlyKeys, minTime,
                                checkMin5);
                break;
            }
            case FINANCE_INTEGRITY: {
                int years = intParam(params, "years", FinanceIntegrityChecker.DEFAULT_YEARS);
                boolean holders = !"false".equalsIgnoreCase(params.get("refreshHolders"));
                boolean fix = "fix".equalsIgnoreCase(params.get("mode"));
                runningRepair = fix;
                lastFinanceSummary = fix
                        ? FinanceIntegrityChecker.repair(logSink(), ledger, cancel, onlyKeys,
                                years, holders)
                        : FinanceIntegrityChecker.scan(logSink(), ledger, cancel, onlyKeys, years);
                break;
            }
            case NAME_INTEGRITY: {
                boolean fix = "fix".equalsIgnoreCase(params.get("mode"));
                runningRepair = fix;
                lastNameSummary = fix
                        ? NameIntegrityChecker.repair(logSink(), ledger, cancel, onlyKeys)
                        : NameIntegrityChecker.scan(logSink(), ledger, cancel, onlyKeys);
                break;
            }
            case MIN_DATA:
                // 走带线路切换的那条：本地分钟文件读不出来时，备用线路会接着试
                runMinDataTask(params.get("source"));
                break;
            case STOCK_NAME: {
                // 名称是跟着行情接口一起取的，所以这里要单独走一条能传来源的路
                String key = ledger.task().title;
                ledger.plan(Collections.singletonList(key));
                ledger.begin(key);
                try {
                    int rows = updater.mainwave.StockNameUpdater.runUpdate(
                            logSink(), params.get("source"));
                    ledger.done(key, "写入 " + rows + " 条");
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    ledger.fail(key, errorMessage(cause));
                    throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
                }
                break;
            }
            case DIVIDEND:
                runConsoleTask(dividendClassOf(params.get("source")), false);
                break;
            case INDEX_DATA:
                // 指数走跟个股同一套取数 / 建库，只是代码清单由任务给定（见 IndexCodes）
                EastMoneyDayFetcher.run(logSink(), ledger, cancel, onlyKeys, 0, true,
                        updater.fetchdata.IndexCodes.all());
                break;
            case ANNOUNCEMENT: {
                int days = intParam(params, "days", 7);
                String key = "最近 " + days + " 天公告";
                ledger.plan(Collections.singletonList(key));
                ledger.begin(key);
                try {
                    AnnouncementFeed.Result result = AnnouncementFeed.sync(days, logSink());
                    ledger.done(key, result.text());
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    ledger.fail(key, errorMessage(cause));
                    throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
                }
                break;
            }
            case SHARE_CAPITAL: {
                String key = "股本数据（按报告期回填）";
                ledger.plan(Collections.singletonList(key));
                ledger.begin(key);
                try {
                    int rows = ShareCapitalSyncService.backfillFromFinance(null);
                    ledger.done(key, "从财报字段回填 " + rows + " 行");
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    ledger.fail(key, errorMessage(cause));
                    throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
                }
                break;
            }
            case FINANCE:
                runFinanceImport(ledger);
                break;
            case HOLDER_SYNC:
                runHolderSync(params, onlyKeys);
                break;
            case RISK_EVENTS:
                runRiskEvents();
                break;
            case RISK_LIFTS:
                runRiskLifts();
                break;
            case RISK_VALUATION:
                runRiskValuation();
                break;
            case PIT_NOTICE:
                runPitNotice();
                break;
            default:
                break;
        }
    }

    private void finishRun(String failure) {
        running = false;
        setRunningUi(false);
        if (ledger != null) {
            if (failure != null) {
                ledger.finish("异常结束: " + failure);
                resultLabel.setForeground(FAIL_COLOR);
                resultLabel.setText("任务失败中断：" + failure);
                appendLog("== 失败: " + failure);
            } else if (cancel.get()) {
                ledger.finishStopped("已手动停止");
                resultLabel.setForeground(UpdaterTheme.WARN_TEXT);
                resultLabel.setText("已停止。已完成 " + ledger.doneCount() + " " + ledger.task().unit
                        + "，剩下的可以点「接着跑」或重开时续跑。");
            } else {
                ledger.finish("");
                resultLabel.setForeground(ledger.failedCount() > 0 ? FAIL_COLOR : OK_COLOR);
                resultLabel.setText(summaryLine(ledger));
                appendLog("== 完成: " + summaryLine(ledger));
            }
            if (tableModel.allSize() != ledger.total()) {
                tableModel.setItems(ledger.items());
            } else {
                tableModel.refresh();
            }
            currentLabel.setText(" ");
            updateProgressUi();
            refreshNavSubtitle(ledger.task());
        }
        retryButton.setEnabled(ledger != null && ledger.failedCount() > 0);
        exportButton.setEnabled(ledger != null && ledger.total() > 0);
        if (cancel.get() && ledger != null) {
            // 手动停止后立刻给出口：不用重开程序，也不用把已经跑完的再刷一遍
            UpdateLedger.Resume stopped = UpdateLedger.loadRun(ledger.dir());
            if (stopped != null) {
                interrupted = stopped;
                showResumeBanner(stopped, "刚停下");
            }
        }
        refreshHistoryPanel();
        refreshStatusBar();

        Runnable after = afterFinish;
        afterFinish = null;
        if (after != null && failure == null && !cancel.get()) {
            after.run();
        }
    }

    private String summaryLine(UpdateLedger ledger) {
        long seconds = (System.currentTimeMillis() - runStartMillis) / 1000;
        if (ledger.task() == UpdateTask.INTEGRITY && lastIntegritySummary != null) {
            // 体检 / 修复的结论不是「完成几只、失败几只」，而是「缺了多少、补了多少」
            return runningRepair
                    ? lastIntegritySummary.describeRepair()
                    : lastIntegritySummary.describeScan();
        }
        if (ledger.task() == UpdateTask.FINANCE_INTEGRITY && lastFinanceSummary != null) {
            return runningRepair
                    ? lastFinanceSummary.describeRepair()
                    : lastFinanceSummary.describeScan();
        }
        if (ledger.task() == UpdateTask.NAME_INTEGRITY && lastNameSummary != null) {
            return runningRepair
                    ? lastNameSummary.describeRepair()
                    : lastNameSummary.describeScan();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("完成 ").append(ledger.doneCount()).append(" · 失败 ").append(ledger.failedCount())
                .append(" · 跳过 ").append(ledger.skippedCount())
                .append(" · 用时 ").append(formatDuration(seconds));
        if (ledger.failedCount() > 0) {
            sb.append("（点「重试失败」只重跑这 ").append(ledger.failedCount()).append(" ")
                    .append(ledger.task().unit).append("）");
        }
        return sb.toString();
    }

    private void setRunningUi(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        startButton.setText(isRunning ? "执行中…" : "开始执行");
        stopButton.setText("停止");
        stopButton.setEnabled(isRunning && canStop(selectedTask));
        // 三组查缺补漏上这个按钮都是「修复缺失」：台账里标红的正是缺数据的那些
        retryButton.setText(ledger != null && ledger.task().isCheck()
                ? "修复缺失" : "重试失败");
        retryButton.setEnabled(!isRunning && ledger != null && ledger.failedCount() > 0);
        exportButton.setEnabled(!isRunning && ledger != null && ledger.total() > 0);
        for (TaskNavButton button : taskButtons.values()) {
            button.setEnabled(!isRunning);
        }
        for (JButton button : checkButtons.values()) {
            button.setEnabled(!isRunning);
        }
    }

    /** 分钟线 / 除权 / 名称是「反射调 main」，中途停不了 */
    private boolean canStop(UpdateTask task) {
        return task == UpdateTask.POST_DOWNLOAD || task == UpdateTask.LATEST_PRICE
                || task == UpdateTask.HISTORY || task == UpdateTask.FINANCE
                || task == UpdateTask.HOLDER_SYNC || task == UpdateTask.RISK_VALUATION
                || task == UpdateTask.PIT_NOTICE
                || task.isCheck();
    }

    private void askStop() {
        if (!running) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "停止当前任务？\n\n已经做完的会留在台账里，没做的保持「未开始」，"
                        + "下次可以接着跑（不会再从头刷一遍）。",
                "停止任务", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        cancel.set(true);
        stopButton.setEnabled(false);
        stopButton.setText("正在停止…");
        appendLog("== 收到停止请求，正在收尾（已完成的记录都会保留）");
        resultLabel.setText("正在停止…");
    }

    // ================================================================== 各类任务的执行体

    /** 反射调某个类的 main（老的六个按钮基本都走这条） */
    private void runConsoleTask(String className, boolean markAnnouncements) throws Exception {
        String key = ledger.task().title;
        ledger.plan(Collections.singletonList(key));
        ledger.begin(key);
        try {
            Method main = Class.forName(className).getMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{});
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            ledger.fail(key, errorMessage(cause));
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        }
        ledger.done(key, "完成");
        if (markAnnouncements) {
            afterFinish = announcementSync(7);
        }
    }

    /**
     * 「分钟数据（5 分钟）」任务：补全市场的 5 分钟线，一条线路不通就换下一条。
     *
     * <p>以前这里反射调 {@code MinDataUpdater.main}，那个 main 自己 catch 住异常弹个框就完了 ——
     * 上层收不到失败，也就没机会切备用线路。现在直连分发器：失败先记进台账再往上抛，
     * 由 {@code startRun} 统一走「换条线路再试」。</p>
     */
    private void runMinDataTask(String sourceId) throws Exception {
        String key = ledger.task().title;
        ledger.plan(Collections.singletonList(key));
        ledger.begin(key);
        try {
            int[] stat = Min5Downloader.run(sourceId, logSink(), cancel, null);
            ledger.done(key, "股票 " + stat[0] + " 只，写入 " + stat[1] + " 根，跳过 " + stat[2] + " 只");
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            ledger.fail(key, errorMessage(cause));
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        }
        afterFinish = announcementSync(7);
    }

    /** 更新完顺手同步最近几天的全市场公告（几秒钟，一天 30 页就够） */
    private Runnable announcementSync(final int days) {
        return new Runnable() {
            @Override
            public void run() {
                updater.f10.F10SyncDialog.syncAnnouncements(UpdaterFrame.this, days, null);
            }
        };
    }

    /**
     * 5 分钟线的线路顺序：本地数据来源配好了就走它，走不通（且允许联网时）再走在线逐只补。
     *
     * <p>顺序在这里定，而不是去问 {@link UpdateSources}：那个表是按任务登记「默认 + 备用」，
     * 这里是一次<b>顺带</b>的动作（用户刚点完补最新价，问了一句要不要连分钟一起补），
     * 没有属于自己的任务行，所以按「本机有没有本地分钟文件」当场决定从哪条起步。</p>
     *
     * @param localOnly 只许读本地文件：盘后下载顺带补这一趟就是这么来的 ——
     *                  收盘后的 5 分钟一律从本地分钟文件读，<b>不联网</b>，
     *                  免得用户以为在补当天数据、实际拉回来的是接口那份更浅的在线数据
     */
    private java.util.List<String> minutesSources(boolean localOnly) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (localMinutesReady()) {
            list.add(Min5Downloader.LOCAL);
        }
        if (!localOnly) {
            // 在线那一档自己还会在东方财富 / 新浪 / 腾讯之间逐只换源
            list.add(Min5Downloader.ONLINE);
        }
        return list;
    }

    /** 本地数据来源里的分钟线目录是否就位（配好了路径、并且确实有 vipdoc） */
    private boolean localMinutesReady() {
        String local = TdxDataDir.configuredPath();
        return !local.isEmpty() && new File(local, "vipdoc").isDirectory();
    }

    /**
     * 5 分钟线：按线路顺序一条条试，第一条通就停。
     *
     * <p><b>为什么放在日线之后</b>：分钟线是另一条入库链路（{@code T_D_Min5}），
     * 以前它是独立任务，天天要人记着再点一次 —— 忘了就是脚本里 30 分钟那些函数静默少数据。</p>
     *
     * <p><b>为什么失败不往上抛</b>：日线已经写进去了，不能因为分钟线把整次任务判成失败；
     * 这里只记日志，用户需要单独补时还能点「分钟数据（5 分钟）」。
     * 线路名不进日志 —— 用户看到的是「这条路没走通，换了另一条」。</p>
     *
     * @param onlyKeys  重试 / 接着跑时只补这几只，null = 全市场
     * @param localOnly 只读本地分钟文件、不联网（盘后下载走这条）；false = 本地不行再在线兜底
     */
    private void syncMinutesAfterPrices(List<String> onlyKeys, boolean localOnly) {
        if (cancel.get()) {
            return;
        }
        java.util.List<String> sources = minutesSources(localOnly);
        if (sources.isEmpty()) {
            logSink().accept("== 跳过 5 分钟线：盘后这一步只读本地分钟文件，"
                    + "而「本地数据来源」还没配好（路径设置里配），所以没有可补的数据；"
                    + "要联网补 5 分钟线请单独跑「分钟数据（5 分钟）」并选在线线路");
            return;
        }
        logSink().accept(localOnly
                ? "== 接着补 5 分钟线（读本地分钟文件，不联网；只补不删，库里已有的老数据不动）"
                : "== 接着补 5 分钟线（先读本地分钟文件，没有再走在线逐只补；只补不删）");
        HangWatchdog.start("补 5 分钟线", logSink());
        String lastFailure = null;
        try {
            for (int i = 0; i < sources.size(); i++) {
                if (cancel.get()) {
                    return;
                }
                if (i > 0) {
                    logSink().accept("== 换下一条路再试一次…");
                }
                try {
                    int[] stat = Min5Downloader.run(sources.get(i), logSink(), cancel, onlyKeys);
                    logSink().accept("5 分钟线完成：股票 " + stat[0] + " 只，写入 " + stat[1]
                            + " 根，跳过 " + stat[2] + " 只");
                    return;
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    lastFailure = errorMessage(cause);
                    Log.out("warn 5 分钟线这条线路没走通：" + lastFailure);
                    logSink().accept("⚠ 这条线路没走通：" + lastFailure);
                }
            }
            logSink().accept("⚠ 5 分钟线没补成功：" + lastFailure
                    + "（日线已写入，不影响；可稍后单独跑「分钟数据（5 分钟）」"
                    + (localOnly ? "，在线线路也能补" : "") + "）");
        } finally {
            HangWatchdog.stop();
        }
    }

    /**
     * 补最新价写完之后问一句要不要连 5 分钟一起补。
     *
     * <p>从后台线程用 invokeAndWait 切回界面线程弹模态框：用户没回答，worker 就停在这里等，
     * 不会在后台偷偷往下跑。默认按钮是「一起更新」—— 分钟线不补，脚本里用 30 分钟的那几个
     * 函数会静默少数据，而且不会报错。</p>
     */
    private boolean askSyncMinutesAfterPrice() {
        final boolean[] agreed = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    Object[] options = new Object[]{"一起更新", "暂不更新"};
                    int pick = JOptionPane.showOptionDialog(UpdaterFrame.this,
                            "最新价已经写进本地库。\n\n"
                                    + "要不要顺便把 5 分钟线也更新到今天？\n\n"
                                    + "只补不删，库里已有的老数据一条都不动。\n"
                                    + "第一次补全市场要几分钟，之后每次只补当天那几根。",
                            "要不要一起更新 5 分钟数据", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                    agreed[0] = (pick == 0);
                }
            });
        } catch (Exception e) {
            agreed[0] = false;
        }
        return agreed[0];
    }


    // ------------------------------------------------------------------ 流通股东

    /** 按季度拉全市场十大流通股东；一个季度一行台账，可以中途停。 */
    private void runHolderSync(Map<String, String> params, List<String> onlyKeys) throws Exception {
        int count = intParam(params, "count", 1);
        boolean skipExisting = !"false".equalsIgnoreCase(params.get("skipExisting"));
        List<Integer> quarters = HolderSync.recentQuarters(Math.max(1, count));
        HolderStore store = new HolderStore();
        if (skipExisting) {
            java.util.Set<Integer> synced = new java.util.HashSet<Integer>(store.periods());
            quarters.removeIf(synced::contains);
        }
        if (onlyKeys != null && !onlyKeys.isEmpty()) {
            quarters.removeIf(quarter -> !onlyKeys.contains(HolderSync.periodDate(quarter)));
        }
        if (quarters.isEmpty()) {
            String key = "没有待同步季度";
            ledger.plan(Collections.singletonList(key));
            ledger.begin(key);
            ledger.done(key, "所选季度本地已有数据；要覆盖就取消「跳过本地已有的季度」");
            return;
        }
        List<String> keys = new ArrayList<String>();
        for (int quarter : quarters) {
            keys.add(HolderSync.periodDate(quarter));
        }
        ledger.plan(keys);
        for (int quarter : quarters) {
            if (cancel.get()) {
                break;
            }
            final String key = HolderSync.periodDate(quarter);
            ledger.begin(key);
            logLine("[股东] 开始 " + HolderSync.periodText(quarter) + "（一季约 5 万行）");
            HolderSync.Result result = HolderSync.sync(quarter, store, new HolderSync.Progress() {
                @Override
                public void onPage(int reportDate, int done, int total, int rows) {
                    if (done == total || done % 200 == 0) {
                        logLine("[股东] " + HolderSync.periodText(reportDate) + " "
                                + done + "/" + total + " 页 · " + rows + " 行");
                    }
                }
            }, new java.util.function.BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return cancel.get();
                }
            });
            if (result.error() != null) {
                ledger.fail(key, result.error());
            } else if (result.cancelled()) {
                ledger.skip(key, result.text());
            } else if (result.failedPages() > 0) {
                ledger.fail(key, result.text());
            } else {
                ledger.done(key, result.text());
            }
        }
    }

    // ------------------------------------------------------------------ 风险与估值

    private void runRiskEvents() throws Exception {
        String key = "风险事件";
        ledger.plan(Collections.singletonList(key));
        ledger.begin(key);
        RiskSync.Result result = RiskSync.syncAnnouncements();
        if (result.note().startsWith("没有公告库")) {
            ledger.skip(key, result.note());
        } else if (result.note().startsWith("读公告库失败")) {
            ledger.fail(key, result.note());
            throw new IllegalStateException(result.note());
        } else {
            ledger.done(key, result.note());
            logLine("[风险] " + result.note());
        }
    }

    private void runRiskLifts() throws Exception {
        int from = RiskSync.plusDays(RiskSync.today(), -90);
        int to = RiskSync.plusMonths(RiskSync.today(), 12);
        String key = "限售解禁";
        ledger.plan(Collections.singletonList(key));
        ledger.begin(key);
        RiskSync.Result result = RiskSync.syncLifts(from, to);
        if (result.note().startsWith("拉取解禁数据失败")) {
            ledger.fail(key, result.note());
            throw new IllegalStateException(result.note());
        }
        ledger.done(key, result.note());
        logLine("[风险] " + result.note());
    }

    /** 一只股票一行台账：能看见做到哪只，失败也能单独定位。 */
    private void runRiskValuation() throws Exception {
        List<String> codes = RiskSync.valuationCodes();
        if (codes.isEmpty()) {
            String key = "估值快照";
            ledger.plan(Collections.singletonList(key));
            ledger.begin(key);
            String note = "没有股本表（T_D_ShareCapital），无法算市值";
            ledger.fail(key, note);
            throw new IllegalStateException(note);
        }
        ledger.plan(codes);
        final int[] logged = {0};
        RiskSync.Result result = RiskSync.syncValuation(
                new RiskSync.Progress() {
                    @Override
                    public void step(int done, int total, String text) {
                        if (done - logged[0] >= 500 || done == total) {
                            logged[0] = done;
                            logLine("[估值] " + text);
                        }
                    }

                    @Override
                    public boolean cancelled() {
                        return cancel.get();
                    }
                },
                new RiskSync.ItemDone() {
                    @Override
                    public void done(String code, RiskStore.Valuation value) {
                        ledger.begin(code);
                        if (value == null) {
                            ledger.skip(code, "没有最新行情或股本");
                        } else {
                            ledger.done(code, "市值 " + amountText(value.totalCap())
                                    + " · PE " + ratioText(value.peTtm())
                                    + " · PB " + ratioText(value.pb()));
                        }
                    }
                });
        logLine("[估值] " + result.note());
        if (result.note().startsWith("计算估值中断")) {
            throw new IllegalStateException(result.note());
        }
    }

    private void runPitNotice() throws Exception {
        String key = "财报披露日";
        ledger.plan(Collections.singletonList(key));
        ledger.begin(key);
        int fromYear = java.time.LocalDate.now().getYear() - 3;
        PitSync.Result result = PitSync.sync(fromYear, new PitSync.Progress() {
            @Override
            public void step(int done, int total, String text) {
                if (done == total || done % 20 == 0) {
                    logLine("[披露日] " + text);
                }
            }

            @Override
            public boolean cancelled() {
                return cancel.get();
            }
        });
        if (result.note().startsWith("已停止")) {
            ledger.skip(key, result.note());
        } else if (result.rows() <= 0) {
            ledger.fail(key, result.note());
            throw new IllegalStateException(result.note());
        } else {
            ledger.done(key, result.note());
            logLine("[披露日] " + result.note());
        }
    }

    private static String amountText(double yuan) {
        if (Double.isNaN(yuan) || Double.isInfinite(yuan)) {
            return "—";
        }
        return String.format("%.1f 亿", yuan / 100000000.0);
    }

    private static String ratioText(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
            return "—";
        }
        return String.format("%.2f", value);
    }

    /** 这次选的来源是不是东方财富（盘后下载 / 补充历史都按它分流） */
    private boolean isEastMoney(Map<String, String> params) {
        return UpdateSources.EASTMONEY.equals(
                UpdateSources.resolveId(UpdateTask.POST_DOWNLOAD, params.get("source")));
    }

    /** 来源 id → 除权任务的具体实现类 */
    private String dividendClassOf(String sourceId) {
        String id = UpdateSources.resolveId(UpdateTask.DIVIDEND, sourceId);
        if (UpdateSources.DZH.equals(id)) {
            return updater.main.down.MainDividendFromDzh.class.getName();
        }
        return updater.main.down.MainDividendFromTdxLocal.class.getName();
    }

    // ------------------------------------------------------------------ 财报

    /** 财报：先在后台查每个季度有没有本地数据，再一次性问覆盖/跳过，最后跑 */
    private void prepareFinanceThenRun(final Map<String, String> params) {
        final String start = params.get("start");
        final String end = params.get("end");
        List<String> dates;
        try {
            dates = financeDates(start, end);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "季度格式", JOptionPane.WARNING_MESSAGE);
            return;
        }
        startButton.setEnabled(false);
        startButton.setText("正在检查…");
        resultLabel.setForeground(UpdaterTheme.MUTED);
        resultLabel.setText("正在向服务器核对这几个季度的财报…");
        appendLog("== 财报: 核对 " + (dates.isEmpty() ? "最新一期" : String.join("、", dates)));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<FinanceImportService.PrepareResult> prepared =
                        new ArrayList<FinanceImportService.PrepareResult>();
                final List<String> failedCheck = new ArrayList<String>();
                try {
                    List<String> targets = dates.isEmpty()
                            ? Collections.singletonList("0") : dates;
                    for (String date : targets) {
                        try {
                            prepared.add("0".equals(date)
                                    ? FinanceImportService.prepareLatest()
                                    : FinanceImportService.prepareByInput(date));
                        } catch (Exception ex) {
                            failedCheck.add(date + "：" + errorMessage(ex));
                        }
                    }
                } catch (Exception ex) {
                    failedCheck.add(errorMessage(ex));
                }
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        startButton.setEnabled(true);
                        startButton.setText("开始执行");
                        if (prepared.isEmpty()) {
                            resultLabel.setForeground(FAIL_COLOR);
                            resultLabel.setText("核对失败，没有可下载的季度");
                            JOptionPane.showMessageDialog(UpdaterFrame.this,
                                    "所有季度的财报检查都失败了:\n" + String.join("\n", failedCheck),
                                    "财报", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        if (!confirmFinance(prepared, failedCheck)) {
                            resultLabel.setText("已取消");
                            return;
                        }
                        pendingFinance = prepared;
                        startRun(UpdateTask.FINANCE, params, null, null);
                    }
                });
            }
        }, "finance-check").start();
    }

    /** 覆盖 / 跳过 一次性问清楚（与原来口径一致） */
    private boolean confirmFinance(List<FinanceImportService.PrepareResult> prepared,
                                   List<String> failedCheck) {
        StringBuilder list = new StringBuilder();
        int existing = 0;
        for (FinanceImportService.PrepareResult p : prepared) {
            list.append("  · ").append(p.reportDate);
            if (p.hasLocalData()) {
                list.append("（本地已有 ").append(p.localRowCount).append(" 条）");
                existing++;
            }
            list.append("\n");
        }
        StringBuilder message = new StringBuilder();
        message.append("将下载以下 ").append(prepared.size())
                .append(" 个季度的财报，导入后自动刷新对应季度的十大流通股东：\n\n")
                .append(list);
        if (existing > 0) {
            message.append("\n本地已有数据的季度怎么处理？");
        }
        if (!failedCheck.isEmpty()) {
            message.append("\n\n以下季度检查失败，会被跳过：\n  ").append(String.join("\n  ", failedCheck));
        }
        if (existing == 0) {
            int ok = JOptionPane.showConfirmDialog(this, message.append("\n\n确定开始下载吗？").toString(),
                    "确认下载财报", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            return ok == JOptionPane.YES_OPTION;
        }
        Object[] options = {"覆盖下载", "跳过已有", "取消"};
        int choice = JOptionPane.showOptionDialog(this, message.toString(), "确认下载财报",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            pendingFinanceOverwrite = true;
            pendingFinanceSkip = false;
            return true;
        }
        if (choice == 1) {
            pendingFinanceOverwrite = false;
            pendingFinanceSkip = true;
            return true;
        }
        return false;
    }

    private boolean pendingFinanceOverwrite = true;
    private boolean pendingFinanceSkip = false;

    private void runFinanceImport(UpdateLedger ledger) throws Exception {
        List<FinanceImportService.PrepareResult> prepared = pendingFinance;
        pendingFinance = null;
        if (prepared == null || prepared.isEmpty()) {
            throw new IllegalStateException("没有核对通过的季度");
        }
        List<String> keys = new ArrayList<String>();
        for (FinanceImportService.PrepareResult p : prepared) {
            keys.add(p.reportDate);
        }
        ledger.plan(keys);

        List<FinanceImportService.ImportResult> imported =
                new ArrayList<FinanceImportService.ImportResult>();
        for (FinanceImportService.PrepareResult p : prepared) {
            if (cancel.get()) {
                return;
            }
            if (pendingFinanceSkip && p.hasLocalData()) {
                ledger.skip(p.reportDate, "本地已有 " + p.localRowCount + " 条，按选择跳过");
                continue;
            }
            ledger.begin(p.reportDate);
            try {
                final String quarter = p.reportDate;
                FinanceImportService.ImportResult result = FinanceImportService.importReport(
                        p, pendingFinanceOverwrite, new java.util.function.Consumer<String>() {
                            @Override
                            public void accept(String message) {
                                logLine("[" + quarter + "] " + message);
                            }
                        });
                imported.add(result);
                ledger.done(p.reportDate, "记录 " + result.rows + " 条"
                        + (result.overwritten ? "（已覆盖）" : ""));
            } catch (Exception ex) {
                ex.printStackTrace();
                ledger.fail(p.reportDate, errorMessage(ex));
            }
        }

        // 十大流通股东：原逻辑是每季导入后刷新，这里保持（在 EDT 上串行做）
        final List<FinanceImportService.ImportResult> done = imported;
        for (final FinanceImportService.ImportResult r : done) {
            if (cancel.get()) {
                break;
            }
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        updater.f10.F10SyncDialog.refreshHolders(UpdaterFrame.this,
                                parseReportDateInt(r.reportDate), r.latestReport, null);
                    } catch (Exception ex) {
                        logLine("[股东] " + r.reportDate + " 刷新失败: " + errorMessage(ex));
                    }
                }
            });
        }
    }

    /** 季度输入 → 报告期列表；空 = 最新一期 */
    private List<String> financeDates(String start, String end) {
        if (start == null || start.trim().isEmpty()) {
            if (end != null && !end.trim().isEmpty()) {
                throw new IllegalArgumentException("请先填起始季度（格式 yyyyMM，如 202603）");
            }
            return new ArrayList<String>();
        }
        String startNorm = FinanceImportService.normalizeQuarterInput(start.trim());
        if (startNorm == null) {
            throw new IllegalArgumentException("起始季度格式不正确: " + start
                    + "\n请输入 yyyyMM（如 202603）或 yyyyMMdd（如 20260331）");
        }
        if (end == null || end.trim().isEmpty()) {
            return new ArrayList<String>(Collections.singletonList(startNorm));
        }
        String endNorm = FinanceImportService.normalizeQuarterInput(end.trim());
        if (endNorm == null) {
            throw new IllegalArgumentException("结束季度格式不正确: " + end
                    + "\n请输入 yyyyMM（如 202603）或 yyyyMMdd（如 20260331）");
        }
        List<String> dates = enumerateQuarters(startNorm, endNorm);
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("结束季度必须不早于起始季度");
        }
        return dates;
    }

    private static List<String> enumerateQuarters(String start, String end) {
        int sy = Integer.parseInt(start.substring(0, 4));
        int sm = Integer.parseInt(start.substring(4, 6));
        int ey = Integer.parseInt(end.substring(0, 4));
        int em = Integer.parseInt(end.substring(4, 6));
        List<String> out = new ArrayList<String>();
        int y = sy;
        int m = sm;
        while (y < ey || (y == ey && m <= em)) {
            int lastDay = java.time.YearMonth.of(y, m).lengthOfMonth();
            out.add(String.format("%04d%02d%02d", y, m, lastDay));
            if (m == 12) {
                m = 3;
                y++;
            } else {
                m += 3;
            }
        }
        return out;
    }

    private static int parseReportDateInt(String reportDate) {
        if (reportDate == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < reportDate.length(); i++) {
            char c = reportDate.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() != 8) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================== 台账操作

    private void selectFilter(LedgerTableModel.Filter filter) {
        JToggleButton chip = chips.get(filter);
        if (chip != null) {
            chip.setSelected(true);
        }
        tableModel.setFilter(filter);
    }

    /**
     * 「重试失败」。
     *
     * <p>查缺补漏这个任务上它换了个意思：台账里标红的就是「缺库 / 打不开 / 缺日期」，
     * 重跑一遍体检没有意义，直接按台账把那几只补齐 —— 按钮文案也跟着变成「修复缺失」。</p>
     */
    private void retryFailed() {
        if (running || ledger == null) {
            return;
        }
        if (ledger.task() == UpdateTask.FINANCE) {
            JOptionPane.showMessageDialog(this,
                    "财报是按季度整批下载的，重试请直接在参数里选好季度重新点「开始执行」。",
                    "重试失败", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (ledger.task().isCheck()) {
            repairMissing();
            return;
        }
        List<UpdateLedger.Item> failed = ledger.failedItems();
        if (failed.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<String>();
        for (UpdateLedger.Item item : failed) {
            keys.add(item.key);
        }
        UpdateTask task = ledger.task();
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.putAll(ledgerParams());
        int answer = JOptionPane.showConfirmDialog(this,
                "只重跑失败的 " + keys.size() + " " + task.unit + "？\n\n"
                        + "台账会新开一张，只列这几" + task.unit + "，成功与否一眼能看到。",
                "重试失败", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        startRun(task, params, null, keys);
    }

    /** 三组查缺补漏各自的第二步：只补台账里标了问题的那些行 */
    private void repairMissing() {
        List<UpdateLedger.Item> failed = ledger.failedItems();
        if (failed.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<String>();
        for (UpdateLedger.Item item : failed) {
            keys.add(item.key);
        }
        UpdateTask task = ledger.task();
        String what;
        switch (task) {
            case INTEGRITY:
                what = "缺日期的只补缺的那几根，缺库的按本地数据来源建库，"
                        + "库文件坏掉的会先改名留底再重建。\n"
                        + "5 分钟线也一起补：本地有分钟文件就从文件补，没有的"
                        + (onlineMin5Box == null || onlineMin5Box.isSelected()
                                ? "改走在线线路逐只取（会多花几分钟）。"
                                : "按设置跳过。")
                        + "\n已经完整的不会动。";
                break;
            case FINANCE_INTEGRITY:
                what = "只补每季查出来「缺的那几只」：财报按代码单条写入、股东只重新抓那几只，"
                        + "同一季其它股票一个字节都不动。\n"
                        + "只有整季没导、或者只导了一半的季度，才会整季重导 / 全市场重刷。\n"
                        + (refreshHoldersOnRepair ? "" : "本次不刷新股东缓存。\n")
                        + "已经齐全的季度不会动。";
                break;
            case NAME_INTEGRITY:
                what = "名称接口一次给全市场，所以会重新抓一遍名称，再逐只复查这几只。\n"
                        + "没有挂载名称库时请先在「路径设置」里配好数据文件夹。";
                break;
            default:
                return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "要补齐这 " + keys.size() + " " + task.unit + "吗？\n\n" + what,
                "修复缺失", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("mode", "fix");
        if (task == UpdateTask.INTEGRITY) {
            params.put("minTime", Long.toString(integrityMinTime));
            params.put("checkMin5", Boolean.toString(
                    checkMin5Box == null || checkMin5Box.isSelected()));
            params.put("onlineMin5", Boolean.toString(
                    onlineMin5Box == null || onlineMin5Box.isSelected()));
        } else if (task == UpdateTask.FINANCE_INTEGRITY) {
            params.put("years", Integer.toString(financeCheckYears));
            params.put("refreshHolders", Boolean.toString(refreshHoldersOnRepair));
        }
        startRun(task, params, null, keys);
    }

    /** 重试时把上次的参数带上（天数、除权来源等） */
    private Map<String, String> ledgerParams() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (daysSpinner != null && daysSpinner.getValue() instanceof Number) {
            params.put("days", Integer.toString(((Number) daysSpinner.getValue()).intValue()));
        }
        if (backupCheck != null) {
            params.put("backup", Boolean.toString(backupCheck.isSelected()));
        }
        if (minCheck != null) {
            params.put("min5", Boolean.toString(minCheck.isSelected()));
        }
        // 沿用画面上当前选的线路；自动切换出来的备用线路只存在 worker 的内存副本里，
        // 这里永远读不到它，所以重试不会把临时线路写进台账
        params.put("source", selectedSourceId == null ? UpdateSources.AUTO : selectedSourceId);
        params.put("checkMin5", Boolean.toString(checkMin5Box == null || checkMin5Box.isSelected()));
        params.put("onlineMin5", Boolean.toString(onlineMin5Box == null || onlineMin5Box.isSelected()));
        return params;
    }

    /** 导出当前台账为 CSV（失败清单排查用，Excel 直接能开） */
    private void exportFailures() {
        if (ledger == null) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File out = new File(UpdaterDataDir.root(),
                "updater-" + ledger.task().id + "-" + stamp + ".csv");
        try {
            writeCsv(out, ledger);
            JOptionPane.showMessageDialog(this,
                    "已导出 " + ledger.total() + " 行台账（失败 "
                            + ledger.failedCount() + " 行）：\n" + out.getAbsolutePath(),
                    "导出清单", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(),
                    "导出清单", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writeCsv(File out, UpdateLedger ledger) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {
            // Excel 认 BOM，否则中文乱码
            writer.write('\uFEFF');
            writer.write("代码/项目,状态,说明或原因,用时毫秒\n");
            for (UpdateLedger.Item item : ledger.items()) {
                writer.write(csv(item.key));
                writer.write(',');
                writer.write(csv(item.status.label));
                writer.write(',');
                writer.write(csv(item.text()));
                writer.write(',');
                writer.write(Long.toString(item.millis));
                writer.write('\n');
            }
        }
    }

    private static String csv(String text) {
        String value = text == null ? "" : text.replace("\"", "\"\"");
        return "\"" + value + "\"";
    }

    private void showSelectedItemDetail() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        UpdateLedger.Item item = tableModel.itemAt(row);
        if (item == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("代码 / 项目: ").append(item.key).append('\n');
        message.append("状态: ").append(item.status.label).append('\n');
        if (!item.detail.isEmpty()) {
            message.append("结果: ").append(item.detail).append('\n');
        }
        if (!item.error.isEmpty()) {
            message.append("失败原因: ").append(item.error).append('\n');
        }
        if (item.millis > 0) {
            message.append("耗时: ").append(item.millis).append(" 毫秒\n");
        }
        JOptionPane.showMessageDialog(this, message.toString(), "台账明细",
                item.status == UpdateLedger.Status.FAILED
                        ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    /** 读一次历史台账，铺到右边只读展示 */
    private void showRunFile(File dir, UpdateLedger.RunInfo info) {
        UpdateLedger.Resume resume = UpdateLedger.loadRun(dir);
        if (resume == null) {
            return;
        }
        List<UpdateLedger.Item> items = new ArrayList<UpdateLedger.Item>();
        for (String key : resume.keys) {
            UpdateLedger.Item item = new UpdateLedger.Item(key);
            UpdateLedger.Status status = resume.statuses.get(key);
            item.status = status == null ? UpdateLedger.Status.PENDING : status;
            String text = resume.details.get(key);
            if (text != null && !text.isEmpty()) {
                if (item.status == UpdateLedger.Status.FAILED) {
                    item.error = text;
                } else {
                    item.detail = text;
                }
            }
            items.add(item);
        }
        tableModel.setItems(items);
        showTable();
        selectFilter(LedgerTableModel.Filter.ALL);
        countsLabel.setText("历史记录 " + info.timeText() + " · 完成 " + info.done
                + " · 失败 " + info.failed + " · 跳过 " + info.skipped + " / 共 " + info.total);
        setChipText(LedgerTableModel.Filter.ALL, "全部 " + info.total);
        setChipText(LedgerTableModel.Filter.FAILED, "失败 " + info.failed);
        setChipText(LedgerTableModel.Filter.PENDING, "未完成 " + info.remaining());
        setChipText(LedgerTableModel.Filter.SKIPPED, "跳过 " + info.skipped);
        setChipText(LedgerTableModel.Filter.DONE, "完成 " + info.done);
        currentLabel.setText(info.finished ? "已结束" : "未跑完（可以接着跑）");
        resultLabel.setForeground(UpdaterTheme.MUTED);
        resultLabel.setText("这是 " + info.timeText() + " 那次的台账（只读）；要重新跑请点右上「开始执行」");
        progressBar.setMaximum(Math.max(1, info.total));
        progressBar.setValue(info.done + info.skipped + info.failed);
        progressBar.setStringPainted(true);
        progressBar.setVisible(true);
        progressBar.setString((info.done + info.skipped + info.failed) + " / " + info.total);
        // 历史台账只读：按钮上的「重试 / 修复」是按当前 ledger 走的，留在历史视图里会误点
        retryButton.setEnabled(false);
        retryButton.setText(info.task != null && info.task.isCheck() ? "修复缺失" : "重试失败");
    }

    private void openLedgerFolder() {
        File dir = UpdateLedger.runsRoot();
        try {
            java.awt.Desktop.getDesktop().open(dir);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "台账目录:\n" + dir.getAbsolutePath(),
                    "台账目录", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ================================================================== 界面刷新

    private void updateProgressUi() {
        if (ledger == null) {
            return;
        }
        int total = ledger.total();
        int done = ledger.doneCount();
        int failed = ledger.failedCount();
        int skipped = ledger.skippedCount();
        int runningNow = ledger.runningCount();
        int pending = ledger.pendingCount();
        int finished = done + failed + skipped;

        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMaximum(total);
            progressBar.setValue(finished);
            progressBar.setStringPainted(true);
            progressBar.setString(finished + " / " + total);
        } else if (running) {
            progressBar.setIndeterminate(true);
            progressBar.setValue(0);
        }

        countsLabel.setText("完成 " + done + " · 失败 " + failed + " · 跳过 " + skipped
                + " · 进行中 " + runningNow + " · 未开始 " + pending);
        if (running) {
            TaskNavButton nav = taskButtons.get(selectedTask);
            if (nav != null) {
                nav.setSubtitle("正在跑 · " + finished + "/" + total);
            }
            List<String> keys = ledger.runningKeys(6);
            long seconds = (System.currentTimeMillis() - runStartMillis) / 1000;
            currentLabel.setText(keys.isEmpty()
                    ? "已用 " + formatDuration(seconds)
                    : "当前: " + String.join("、", keys) + (keys.size() >= 6 ? " …" : "")
                    + " · 已用 " + formatDuration(seconds));
        } else if (currentLabel.getText().startsWith("当前")) {
            currentLabel.setText(" ");
        }

        setChipText(LedgerTableModel.Filter.ALL, "全部 " + total);
        setChipText(LedgerTableModel.Filter.FAILED, "失败 " + failed);
        setChipText(LedgerTableModel.Filter.PENDING, "未完成 " + (pending + runningNow));
        setChipText(LedgerTableModel.Filter.SKIPPED, "跳过 " + skipped);
        setChipText(LedgerTableModel.Filter.DONE, "完成 " + done);

        JToggleButton failChip = chips.get(LedgerTableModel.Filter.FAILED);
        if (failChip != null) {
            failChip.setForeground(failed > 0
                    ? (failChip.isSelected() ? FAIL_COLOR : FAIL_COLOR)
                    : UpdaterTheme.MUTED);
        }
    }

    private void setChipText(LedgerTableModel.Filter filter, String text) {
        JToggleButton chip = chips.get(filter);
        if (chip != null && !text.equals(chip.getText())) {
            chip.setText(text);
        }
    }

    private void refreshNavSubtitle(UpdateTask task) {
        TaskNavButton button = taskButtons.get(task);
        if (button == null || ledger == null) {
            return;
        }
        String text;
        if (ledger.failedCount() > 0) {
            text = "刚跑完 · 失败 " + ledger.failedCount() + " " + task.unit;
        } else if (cancel.get()) {
            text = "已停止 · 还剩 " + ledger.pendingCount() + " " + task.unit;
        } else {
            text = "刚跑完 · 完成 " + ledger.doneCount() + " " + task.unit;
        }
        button.setSubtitle(text);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        }
        return (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分";
    }

    /** 台账表格渲染：状态上色，失败原因标红 */
    private final class ItemRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(source, value, isSelected, hasFocus, row, column);
            UpdateLedger.Item item = tableModel.itemAt(row);
            setBorder(new EmptyBorder(0, 6, 0, 6));
            if (item == null) {
                return this;
            }
            Color color = UpdaterTheme.TEXT;
            switch (column) {
                case 0:
                    setFont(UpdaterTheme.regular(12f));
                    color = UpdaterTheme.INK;
                    break;
                case 1:
                    setFont(UpdaterTheme.bold(12f));
                    color = statusColor(item.status, isSelected);
                    break;
                case 2:
                    setFont(UpdaterTheme.regular(12f));
                    color = item.status == UpdateLedger.Status.FAILED ? FAIL_COLOR
                            : (item.status == UpdateLedger.Status.SKIPPED ? UpdaterTheme.MUTED : UpdaterTheme.TEXT);
                    break;
                default:
                    setFont(UpdaterTheme.regular(11f));
                    color = UpdaterTheme.FAINT;
                    break;
            }
            if (!isSelected) {
                setForeground(color);
            } else {
                setForeground(item.status == UpdateLedger.Status.FAILED && column != 1
                        ? FAIL_COLOR : UpdaterTheme.TEXT_STRONG);
            }
            setToolTipText(item.text().isEmpty() ? null : "<html><div width='420'>"
                    + escape(item.text()) + "</div></html>");
            return this;
        }

        private Color statusColor(UpdateLedger.Status status, boolean selected) {
            if (selected) {
                return UpdaterTheme.TEXT_STRONG;
            }
            switch (status) {
                case DONE:
                    return OK_COLOR;
                case FAILED:
                    return FAIL_COLOR;
                case RUNNING:
                    return UpdaterTheme.ACCENT;
                case SKIPPED:
                    return UpdaterTheme.MUTED;
                default:
                    return UpdaterTheme.PENDING;
            }
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ================================================================== 环境检查

    /** 备份文件夹缺失时「设置」按钮用的目录选择框 */
    private File chooseBackupDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择数据备份文件夹");
        String current = BackupDir.configured();
        if (!current.isEmpty()) {
            File dir = new File(current);
            if (dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            }
        }
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile() : null;
    }

    private boolean prepareDataDir() {
        try {
            UpdaterDataDir.Paths paths = UpdaterDataDir.apply();
            if (paths.createdAnything()) {
                appendLog("已创建数据文件夹: " + paths.root + "（股票库目录: " + paths.stockDir + "）");
                refreshStatusBar();
            }
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "数据文件夹不可用：" + ex.getMessage()
                            + "\n\n数据目录由启动参数 --data-dir 传入，"
                            + "请检查该参数指向的位置是否正确。",
                    "数据文件夹", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean ensureTdxDataDir(boolean needVipdoc) {
        try {
            if (needVipdoc) {
                TdxDataDir.requireVipdoc();
            } else {
                TdxDataDir.require();
            }
            return true;
        } catch (updater.cfg.ConfigException ex) {
            int open = JOptionPane.showConfirmDialog(this,
                    ex.getMessage() + "\n\n现在就打开「路径设置」选本地数据来源目录吗？",
                    "本地数据来源未配置", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (open == JOptionPane.YES_OPTION) {
                new PathSettingsDialog(this, null).setVisible(true);
            }
            return false;
        }
    }

    // ================================================================== 日志

    private java.util.function.Consumer<String> logSink() {
        return new java.util.function.Consumer<String>() {
            @Override
            public void accept(String message) {
                if (message != null) {
                    appendLog(message);
                }
            }
        };
    }

    private void logLine(String message) {
        appendLog(message);
    }

    private void installLogRedirect() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(new LogOutputStream(), true));
        System.setErr(new PrintStream(new LogOutputStream(), true));
        appendLog("== 数据更新程序就绪 ==");
    }

    private void restoreStreams() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        if (originalErr != null) {
            System.setErr(originalErr);
        }
    }

    /** 字节流按行切分后写进日志区（中文多字节不会被拆坏） */
    private class LogOutputStream extends java.io.OutputStream {

        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
            if (b == '\n') {
                flushLine();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() {
            if (buffer.size() > 0) {
                flushLine();
            }
        }

        private void flushLine() {
            String line = new String(buffer.toByteArray(), java.nio.charset.Charset.defaultCharset());
            buffer.reset();
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
                end--;
            }
            appendLog(end == line.length() ? line : line.substring(0, end));
        }
    }

    private final StringBuilder pendingLog = new StringBuilder();
    private boolean logFlushScheduled;

    /**
     * 追加一行日志（任意线程调用都安全）。
     *
     * <p>先攒批、一次交给 EDT：全市场跑起来每只都可能打一行，一行一个 invokeLater
     * 会把事件队列塞爆，界面看起来就是「日志停了」。</p>
     */
    private void appendLog(final String line) {
        if (logArea == null || line == null) {
            return;
        }
        boolean schedule = false;
        synchronized (pendingLog) {
            pendingLog.append(line).append('\n');
            if (pendingLog.length() > 2_000_000) {
                pendingLog.delete(0, pendingLog.length() - 1_000_000);
            }
            if (!logFlushScheduled) {
                logFlushScheduled = true;
                schedule = true;
            }
        }
        if (schedule) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    flushPendingLog();
                }
            });
        }
    }

    private void flushPendingLog() {
        String text;
        synchronized (pendingLog) {
            text = pendingLog.toString();
            pendingLog.setLength(0);
            logFlushScheduled = false;
        }
        if (logArea == null || text.isEmpty()) {
            return;
        }
        logArea.append(text);
        try {
            javax.swing.text.Document doc = logArea.getDocument();
            if (doc.getLength() > 400_000) {
                doc.remove(0, 200_000);
            }
        } catch (javax.swing.text.BadLocationException ignored) {
            // 截断失败不影响显示
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ================================================================== 窗口 / 工具

    private void installWindowBehaviour() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (running && !confirmQuitWhileRunning()) {
                    return;
                }
                cancel.set(true);
                flushLedger();
                restoreStreams();
                dispose();
                // 后台线程是 daemon，这里退出不会有残留进程
                System.exit(0);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                restoreStreams();
            }
        });

        // kill / 注销 / 关机：至少把台账最后几秒的记录落下去
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                flushLedger();
            }
        }, "ledger-shutdown"));
    }

    private boolean confirmQuitWhileRunning() {
        int answer = JOptionPane.showConfirmDialog(this,
                "任务正在运行，关掉就是中断。\n\n"
                        + "已经完成的记录都在台账里，下次打开会提示「还剩多少」，可以接着跑。\n确定关掉吗？",
                "退出", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    private void flushLedger() {
        UpdateLedger current = ledger;
        if (current != null && !current.isFinished()) {
            // 不标完成：下次启动才能识别成「中断」，给出续跑入口
            current.flushNow();
        }
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable cause = ex;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String errorMessage(Throwable ex) {
        Throwable cause = unwrap(ex);
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? cause.toString() : message;
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            String value = params == null ? null : params.get(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longParam(Map<String, String> params, String key, long fallback) {
        try {
            String value = params == null ? null : params.get(key);
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 查缺补漏的「检查范围」→ 时间下限（yyyyMMdd，0 = 全部历史）。
     *
     * <p>老库里的缺口往往在十几年前，全补一遍要写几百万行；用户多半只关心最近这几年。
     * 这里按自然年往前推，1 月 1 日作为下限，跟本地数据来源里日线的粒度一致。</p>
     */
    private static long integrityMinTimeOf(int index) {
        if (index <= 0 || index >= INTEGRITY_RANGE_YEARS.length) {
            return 0L;
        }
        int years = INTEGRITY_RANGE_YEARS[index];
        if (years <= 0) {
            return 0L;
        }
        int year = java.time.LocalDate.now().getYear() - years;
        return year * 10000L + 101L;
    }

    /** 财报体检的「检查范围」→ 看最近几年（越界按默认 3 年） */
    private static int financeCheckYearsOf(int index) {
        if (index < 0 || index >= FINANCE_CHECK_YEARS.length) {
            return FinanceIntegrityChecker.DEFAULT_YEARS;
        }
        return FINANCE_CHECK_YEARS[index];
    }
}
