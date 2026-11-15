package updater.f10;

import updater.log.Log;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F10 数据的两个批量任务的小界面：公告同步（几秒）与股东/板块刷新（几分钟，可取消）。
 *
 * <p><b>股东与板块的刷新时机不一样</b>：股东每个报告期整批换代，财报导入哪个季度就刷哪个季度；
 * 板块只在「导入的是库里最新一期」时才有必要刷（补历史季度时板块不会变）。
 * 所以 {@link #refreshHolders(Window, int, boolean, Runnable)} 把「刷哪些」与「股东报告期」都开放出来。</p>
 *
 * <p>放在 {@code updater.f10} 而不是 MainFrame 里，是为了让 K 线图、F10 画面也能复用同一套
 * 进度与结果提示，不用各写一份。</p>
 */
public final class F10SyncDialog {

    private F10SyncDialog() {
    }

    /**
     * 同步全市场公告（最近 {@code days} 天）。几十页请求，一两秒就完了，所以不给取消按钮。
     *
     * @param onDone 完成回调（在 EDT 上执行），可为 null。用于「跑完再弹总结」的主流程
     */
    public static void syncAnnouncements(Window owner, int days, Runnable onDone) {
        final JDialog dialog = base(owner, "正在同步公告…", 420, 130);
        final JLabel label = new JLabel("正在翻页获取全市场公告…", SwingConstants.CENTER);
        final JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        panel.add(label, BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        dialog.setContentPane(panel);

        // 顺序很重要：modal 对话框的 setVisible(true) 会开嵌套事件泵、把这个方法卡住，
        // 所以后台线程必须【先启动】再显示窗口，否则永远走不到 start()（血泪教训）
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AnnouncementFeed.Result result = AnnouncementFeed.sync(days,
                            new java.util.function.Consumer<String>() {
                                @Override
                                public void accept(String text) {
                                    SwingUtilities.invokeLater(new Runnable() {
                                        @Override
                                        public void run() {
                                            label.setText(text);
                                        }
                                    });
                                }
                            });
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dispose();
                            if (onDone != null) {
                                onDone.run();
                            } else {
                                info(owner, result.text());
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.error("公告同步失败：" + e.getMessage());
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dispose();
                            JOptionPane.showMessageDialog(owner,
                                    "公告同步失败：\n" + e.getMessage()
                                            + "\n\n已入库的公告不受影响，可稍后重试。",
                                    "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "f10-announcement-feed");
        worker.setDaemon(true);
        worker.start();
        dialog.setVisible(true);
    }

    /**
     * 刷新全市场十大股东 / 板块题材（不限期别，股东取接口最新一期）。
     *
     * @param onDone 完成回调（在 EDT 上执行），参数是总结文字
     */
    public static void refreshHoldersAndConcepts(Window owner, Runnable onDone) {
        refreshHolders(owner, 0, true, onDone);
    }

    /**
     * 刷新全市场股东，可选是否连板块一起刷。
     *
     * <p>为什么能分开：股东每个报告期整批换代，财报导入哪个季度就要刷哪个季度；
     * 板块只在「导入的是库里最新一期」时才可能变，补历史季度时不该白跑几千次请求。</p>
     *
     * @param reportDate      股东报告期 yyyyMMdd；0 表示取接口最新一期
     * @param includeConcepts 是否同时刷板块题材
     * @param onDone          完成回调（在 EDT 上执行），可为 null
     */
    public static void refreshHolders(Window owner, int reportDate, boolean includeConcepts,
                                      Runnable onDone) {
        final List<String> codes = F10BulkSync.localStockCodes();
        if (codes.isEmpty()) {
            JOptionPane.showMessageDialog(owner,
                    "未挂载股票数据，或挂载目录里没有日线库，没有可刷新的股票。",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String thing = includeConcepts ? "股东与板块" : "股东";
        final String periodTip = reportDate > 0 ? "（报告期 " + reportDate + "）" : "";

        // 清缓存必须在真正开始刷之前：不清的话 TTL 还没到，全部命中旧缓存、一只都抓不到新的。
        // 只清本次要刷的那一类，别顺手把另一类也清掉（补旧季度财报时板块完全没变，清掉等于白拉几千次）。
        try {
            F10Store.invalidateHolders();
            if (includeConcepts) {
                F10Store.invalidateConcepts();
            }
        } catch (Exception ignored) {
            // 清缓存失败不影响后面按需获取
        }

        final JDialog dialog = base(owner, "刷新" + thing + "数据", 460, 150);
        final JLabel label = new JLabel("准备刷新 " + codes.size() + " 只股票的" + thing + periodTip + "…",
                SwingConstants.CENTER);
        final JProgressBar bar = new JProgressBar(0, codes.size());
        bar.setStringPainted(true);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> {
            cancelled.set(true);
            cancel.setEnabled(false);
            label.setText("正在停止…已抓到的不受影响");
        });

        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(cancel);
        panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setSize(460, 160);
        dialog.setLocationRelativeTo(owner);

        // 同样：先起线程再 setVisible，否则 modal 把方法卡在这里，worker.start() 永远不执行
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final F10BulkSync.Summary summary = F10BulkSync.refresh(codes, reportDate,
                        true, includeConcepts,
                        new F10BulkSync.Progress() {
                            @Override
                            public void update(int done, int total, String code, int fetched) {
                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        bar.setValue(done);
                                        bar.setString(done + " / " + total);
                                        label.setText("正在刷新 " + code
                                                + "（已成功 " + fetched + " 项）");
                                    }
                                });
                            }
                        }, cancelled::get);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dispose();
                        String text = summary.text();
                        info(owner, cancelled.get() ? "已取消。" + text : text);
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                });
            }
        }, "f10-bulk-sync");
        worker.setDaemon(true);
        worker.start();
        dialog.setVisible(true);
    }

    /**
     * 财报导入后问一句是否顺手刷新股东（可选连板块）。
     *
     * <p>不默认硬跑：全市场一轮要十几分钟，安静地跑会让人以为程序卡死。
     * 说明里点出「下一季之前不用再刷」，用户第二次就会毫不犹豫点「刷新」。</p>
     *
     * @param reportDate      刚导入的财报报告期，股东按这一期刷新
     * @param includeConcepts 是否把板块一起刷（只有导入的是库里最新一期才为 true）
     */
    public static void offerRefreshAfterFinance(Window owner, String reportDate,
                                                boolean includeConcepts) {
        int count = F10BulkSync.localStockCodes().size();
        if (count == 0) {
            return;
        }
        final int date = parseReportDate(reportDate);
        String periodText = date > 0 ? String.valueOf(date) : "最新一期";
        // 库里已经有多少条：说清「有什么、有多少、要不要覆盖」，别让人凭感觉点
        int cached = F10Store.countCache(F10Store.holderKind(date));
        int total = F10Store.countCachePrefix("holder");

        Object[] options = {"覆盖刷新", "保留现有"};
        StringBuilder message = new StringBuilder();
        if (cached > 0) {
            message.append("本地已有报告期 ").append(periodText).append(" 的股东数据 ")
                    .append(cached).append(" 只");
            if (total > cached) {
                message.append("（各报告期合计 ").append(total).append(" 条）");
            }
            message.append("。\n是否重新联网获取并覆盖？\n\n");
        } else {
            message.append("本地还没有报告期 ").append(periodText).append(" 的股东数据。")
                    .append("是否现在刷新？\n\n");
        }
        message.append("范围: ").append(count).append(" 只（本地有日线库的股票）\n");
        message.append("内容: 十大流通股东");
        message.append(includeConcepts
                ? "\n      板块题材（导入的是库里最新一期，可能已变）"
                : "\n      不含板块题材（导入的是旧季度，板块不会变）");
        // 每只的请求数：股东 1 个，连板块 2 个
        message.append("\n耗时: 约 ").append(Math.max(1, count * (includeConcepts ? 2 : 1) / 600))
                .append(" 分钟，可随时取消");
        int choice = JOptionPane.showOptionDialog(owner,
                message.toString(),
                "刷新股东" + (includeConcepts ? "与板块" : ""),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            refreshHolders(owner, date, includeConcepts, null);
        }
    }

    /** 报告期字符串转 yyyyMMdd；解析不出来返回 0（= 最新一期） */
    private static int parseReportDate(String reportDate) {
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

    private static JDialog base(Window owner, String title, int width, int height) {
        JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(new Dimension(width, height));
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private static void info(Window owner, String text) {
        JOptionPane.showMessageDialog(owner, text, "提示", JOptionPane.INFORMATION_MESSAGE);
    }
}
