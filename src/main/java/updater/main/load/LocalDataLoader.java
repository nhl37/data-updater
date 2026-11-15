package updater.main.load;

import updater.cfg.ConfigException;
import updater.db.DBConnection;
import updater.db.DBException;
import updater.db.DataTableManager;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 从本地 CSV 文件加载 A 股行情数据到数据库。
 *
 * 文件名称格式: A股行情_2026-09-28.csv
 * 文件数据格式:
 *   市场代码,代码,名称,市场,日期,开盘,最高,最低,收盘,前收盘,涨跌幅(%),振幅(%),成交量(手),成交额(万元),换手率(%)
 *   bj920000,920000,XD安徽凤,北交所,2026-09-29,13.7,15,13.7,14.32,13.7,4.5255,9.4891,14039.0,2033.39,2.44
 *   sh688356,688356,键凯科技,上交所,2026-09-29,95.22,101.57,93.2,95.79,99.45,-3.6802,8.4163,5752194.0,55790.49,9.48
 *
 * 交互:
 *   单个日期: 处理单个文件
 *   批量处理: 处理一个文件夹的所有数据
 */
public class LocalDataLoader {

    /** 成交量单位换算: 文件为“手”，数据库(TDX)为“股”，1手 = 100股 */
    private static final int VOLUME_LOT_TO_SHARE = 100;
    /** 成交额单位换算: 文件为“万元”，数据库为“元”，1万元 = 10000元 */
    private static final long AMOUNT_WAN_TO_YUAN = 10000L;

    // 各列下标
    private static final int COL_MARKET_CODE = 0; // 市场代码  bj920000
    private static final int COL_DATE = 4;        // 日期      2026-09-29
    private static final int COL_OPEN = 5;        // 开盘
    private static final int COL_HIGH = 6;        // 最高
    private static final int COL_LOW = 7;         // 最低
    private static final int COL_CLOSE = 8;       // 收盘
    private static final int COL_VOLUME = 12;     // 成交量(手)
    private static final int COL_AMOUNT = 13;     // 成交额(万元)
    private static final int MIN_COLUMNS = 14;

    private LocalDataLoader() {
    }

    /**
     * 弹出“从本地加载数据”对话框:
     *   单个日期: 处理单个文件
     *   批量处理: 处理一个文件夹的所有数据
     */
    public static void showLoadDialog(final JFrame parent) {
        Object[] options = {"单个日期（单文件）", "批量处理（文件夹）", "取消"};
        int choice = JOptionPane.showOptionDialog(
                parent,
                "请选择加载方式:\n\n" +
                        "单个日期: 处理单个文件\n" +
                        "批量处理: 处理一个文件夹的所有数据",
                "从本地加载数据",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 0) {
            File file = chooseCsvFile(parent);
            if (file != null) {
                runLoad(parent, file, false);
            }
        } else if (choice == 1) {
            File dir = chooseFolder(parent);
            if (dir != null) {
                runLoad(parent, dir, true);
            }
        }
    }

    /** 选择单个 CSV 文件 */
    private static File chooseCsvFile(JFrame parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择行情数据文件");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV 文件 (*.csv)", "csv"));
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    /** 选择文件夹 */
    private static File chooseFolder(JFrame parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择行情数据文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    /** 后台执行加载，并显示进度与结果 */
    private static void runLoad(final JFrame parent, final File target, final boolean batch) {
        final JDialog progressDialog = createProgressDialog(parent,
                batch ? "正在批量处理文件夹..." : "正在处理文件...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final LoadResult total = new LoadResult();
                try {
                    if (!batch) {
                        total.merge(loadFile(target));
                    } else {
                        File[] files = target.listFiles(new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                return name.toLowerCase().endsWith(".csv");
                            }
                        });
                        if (files == null || files.length == 0) {
                            total.fileNameList.add("(文件夹内没有 csv 文件)");
                        } else {
                            Arrays.sort(files, new Comparator<File>() {
                                @Override
                                public int compare(File o1, File o2) {
                                    return o1.getName().compareTo(o2.getName());
                                }
                            });
                            for (File f : files) {
                                try {
                                    total.merge(loadFile(f));
                                } catch (Exception ex) {
                                    total.fileCount++;
                                    total.failCount++;
                                    total.fileNameList.add(f.getName() + " 失败: " + ex.getMessage());
                                }
                            }
                        }
                    }
                } catch (final Exception ex) {
                    ex.printStackTrace();
                    total.fileNameList.add("加载失败: " + ex.getMessage());
                } finally {
                    if (total.rowCount > 0) {
                        // 本地行情也是价格数据来源，脚本按这个时间判断当天K线是否完整
                        updater.fetchdata.PriceUpdateClock.markUpdated();
                        // 「盘后数据下载 → 从本地加载」同样算跑过一次盘后流程
                        updater.fetchdata.AfterHoursDownloadClock.markToday();
                    }
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            progressDialog.dispose();
                            showSummary(parent, total);
                        }
                    });
                }
            }
        }).start();

        progressDialog.setVisible(true);
    }

    /**
     * 加载单个 CSV 文件到数据库
     */
    public static LoadResult loadFile(File csvFile) throws IOException {
        LoadResult result = new LoadResult();
        result.fileCount = 1;
        result.fileNameList.add(csvFile.getName());

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(csvFile), StandardCharsets.UTF_8));

            String line;
            boolean isFirst = true;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = removeBom(line);
                if (isFirst) {
                    isFirst = false;
                    // 跳过表头（表头包含“市场代码/代码/日期”等关键字）
                    if (line.contains("市场代码") || line.contains("代码")
                            || line.toLowerCase().contains("code")) {
                        continue;
                    }
                    // 没有表头则按数据处理
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    if (parseAndStore(line, result)) {
                        result.rowCount++;
                    } else {
                        result.skipCount++;
                    }
                } catch (Exception ex) {
                    result.failCount++;
                    System.err.println("[" + csvFile.getName() + "] 第 " + lineNo + " 行处理失败: "
                            + line + " -> " + ex.getMessage());
                }
            }
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException ignore) {
                }
            }
        }
        return result;
    }

    /** 解析一行并写入数据库，返回 true 表示写入成功 */
    private static boolean parseAndStore(String line, LoadResult result) throws DBException, ConfigException {
        String[] cols = splitCsv(line);
        if (cols.length < MIN_COLUMNS) {
            throw new IllegalArgumentException("列数不足(" + cols.length + ")，期望至少 " + MIN_COLUMNS);
        }

        String kCode = normalizeCode(cols[COL_MARKET_CODE]);
        int time = parseDate(cols[COL_DATE]);
        double open = parseDouble(cols[COL_OPEN]);
        double high = parseDouble(cols[COL_HIGH]);
        double low = parseDouble(cols[COL_LOW]);
        double close = parseDouble(cols[COL_CLOSE]);
        long vol = Math.round(parseDouble(cols[COL_VOLUME]) * VOLUME_LOT_TO_SHARE);
        long amo = Math.round(parseDouble(cols[COL_AMOUNT]) * AMOUNT_WAN_TO_YUAN);

        if (close == 0) {
            return false;
        }
        if (open == 0) open = close;
        if (high == 0) high = close;
        if (low == 0) low = close;

        store(kCode, time, open, close, high, low, vol, amo, result);
        return true;
    }

    /** 写入数据库（价格按“分”存储） */
    private static void store(String kCode, int time, double open, double close, double high, double low,
                              long vol, long amo, LoadResult result) throws DBException, ConfigException {
        int openP = (int) Math.round(open * 100);
        int closeP = (int) Math.round(close * 100);
        int highP = (int) Math.round(high * 100);
        int lowP = (int) Math.round(low * 100);

        File dbFile = new File(DBConnection.dbFilePath(kCode));
        boolean exists = dbFile.exists();

        // 数据库不存在时自动创建
        Connection conn = DBConnection.connect(kCode, true);
        try {
            DataTableManager dtm = new DataTableManager();
            dtm.setConn(conn);
            if (!exists) {
                dtm.createDataTable(kCode);
            }
            dtm.insertOrUpdate(time, openP, closeP, highP, lowP, vol, amo);

            result.stockSet.add(kCode);
            result.daySet.add(time);
        } finally {
            try {
                conn.close();
            } catch (Exception ignore) {
            }
        }
    }

    /** 规范化股票代码，缺失市场前缀时按首位推断 */
    private static String normalizeCode(String code) {
        code = code == null ? "" : code.trim().toLowerCase();
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")) {
            return code;
        }
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) {
            return "sh" + code;
        }
        if (code.startsWith("8") || code.startsWith("4")) {
            return "bj" + code;
        }
        return "sz" + code;
    }

    /** 解析日期: 2026-09-29 / 2026/9/9 / 20260929 -> 20260929 */
    private static int parseDate(String s) {
        s = s == null ? "" : s.trim();
        if (s.contains("-")) {
            String[] p = s.split("-");
            if (p.length >= 3) {
                int y = Integer.parseInt(p[0].trim());
                int m = Integer.parseInt(p[1].trim());
                int d = Integer.parseInt(p[2].trim());
                return y * 10000 + m * 100 + d;
            }
        }
        return Integer.parseInt(s.replace("/", "").replace(".", "").trim());
    }

    private static double parseDouble(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) {
            return 0;
        }
        return Double.parseDouble(s);
    }

    /** 简单 CSV 解析，支持引号包裹的字段 */
    private static String[] splitCsv(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                parts.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        parts.add(sb.toString());
        return parts.toArray(new String[0]);
    }

    /** 去除 UTF-8 BOM */
    private static String removeBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private static JDialog createProgressDialog(JFrame parent, String message) {
        final JDialog dialog = new JDialog(parent, "请稍候", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(320, 130);
        dialog.setLocationRelativeTo(parent);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(new JLabel(message, SwingConstants.CENTER), BorderLayout.CENTER);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        return dialog;
    }

    private static void showSummary(JFrame parent, LoadResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("本地数据加载完成!\n\n");
        sb.append("文件数: ").append(r.fileCount).append("\n");
        sb.append("成功写入记录数: ").append(r.rowCount).append("\n");
        sb.append("跳过记录数: ").append(r.skipCount).append("\n");
        sb.append("失败记录数: ").append(r.failCount).append("\n");
        sb.append("涉及股票数: ").append(r.stockSet.size()).append("\n");
        if (r.daySet.size() <= 10) {
            sb.append("涉及日期: ").append(r.daySet).append("\n");
        } else {
            sb.append("涉及日期数: ").append(r.daySet.size())
                    .append(" (").append(r.daySet.first())
                    .append(" ~ ").append(r.daySet.last()).append(")\n");
        }

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(420, 220));

        JOptionPane.showMessageDialog(parent, sp, "加载结果", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 加载结果统计 */
    public static class LoadResult {
        public int fileCount;
        public int rowCount;
        public int skipCount;
        public int failCount;
        public final List<String> fileNameList = new ArrayList<>();
        public final TreeSet<String> stockSet = new TreeSet<>();
        public final TreeSet<Integer> daySet = new TreeSet<>();

        public void merge(LoadResult other) {
            if (other == null) {
                return;
            }
            this.fileCount += other.fileCount;
            this.rowCount += other.rowCount;
            this.skipCount += other.skipCount;
            this.failCount += other.failCount;
            this.fileNameList.addAll(other.fileNameList);
            this.stockSet.addAll(other.stockSet);
            this.daySet.addAll(other.daySet);
        }
    }

    /**
     * 命令行入口（测试用）:
     *   java updater.main.load.LocalDataLoader <csv文件或文件夹>
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: java updater.main.load.LocalDataLoader <csv文件或文件夹>");
            return;
        }
        File target = new File(args[0]);
        LoadResult result = new LoadResult();
        if (target.isDirectory()) {
            File[] files = target.listFiles((d, n) -> n.toLowerCase().endsWith(".csv"));
            if (files != null) {
                for (File f : files) {
                    result.merge(loadFile(f));
                }
            }
        } else {
            result = loadFile(target);
        }
        System.out.println("文件数=" + result.fileCount
                + ", 写入=" + result.rowCount
                + ", 跳过=" + result.skipCount
                + ", 失败=" + result.failCount
                + ", 股票数=" + result.stockSet.size()
                + ", 日期数=" + result.daySet.size());
    }
}
