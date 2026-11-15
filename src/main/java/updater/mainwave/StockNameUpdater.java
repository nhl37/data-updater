package updater.mainwave;

import updater.fetchdata.EasyQuotConfig;
import updater.fetchdata.RealtimeQuoteFetcher;
import updater.log.Log;
import updater.mainwave.dao.StockInfoDAO;
import updater.mount.MountConfig;
import updater.name.StockNameStore;

import javax.swing.*;
import java.awt.BorderLayout;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 股票名称更新器：用腾讯行情接口拉全市场行情里的「代码 + 名称」，
 * 写进挂载的股票名称库（{@link StockNameStore}）。
 *
 * <p><b>名称库是挂载才启用的</b>：没挂就直接提示“未挂载，无法更新”并返回，
 * 不会偷偷建一个程序目录下的默认库（那样界面永远读不到，等于白更新）。</p>
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #runUpdate(Consumer)} —— 无界面核心，日志回调出去。控制台和
 *       StockDataUpdater 的「更新股票名称」按钮都走它（见 {@code UpdaterFrame}）。</li>
 *   <li>{@link #updateStockNames(JFrame)} —— 主升浪画面菜单用的带进度框版本。</li>
 * </ul>
 *
 * <p>股票代码来源是当前生效的股票数据目录（{@code MountConfig.currentDbDir()}），
 * 不再是写死的 {@code data/db}，这样挂载到外部盘也能正确更新。
 */
public class StockNameUpdater {

    /** 名称 CSV 的输出目录（中间产物，导入名称库后不再被读取） */
    private static final String CSV_DIR = "data/easyquotation";

    /** 无界面版本用不到实例状态 */
    public StockNameUpdater() {
    }

    /**
     * 更新股票名称（核心逻辑，不碰 Swing）。
     *
     * @param log 进度/日志回调，可传 {@code System.out::println}
     * @return 写入名称库的条数
     * @throws Exception 配置缺失、行情获取失败、结果文件为空等
     */
    public static int runUpdate(Consumer<String> log) throws Exception {
        return runUpdate(log, null);
    }

    /**
     * 指定线路的版本（默认线路失败后的临时切换用，画面上没有对应控件）。
     *
     * <p>名称本来就是从行情返回里取的，所以最新价支持哪几条线路，这里就支持哪几条。</p>
     *
     * @param sourceOverride 本次临时使用的线路 id；空或 null 表示跟配置走默认线路
     */
    public static int runUpdate(Consumer<String> log, String sourceOverride) throws Exception {
        Consumer<String> out = log == null ? message -> { } : log;

        // 0. 名称库是「挂载才启用」：没挂就没地方写，也不该往 data/ 丢一个没人读的 CSV
        if (!StockNameStore.enabled()) {
            out.accept("未挂载股票名称库，无法更新。\n"
                    + "请先在「挂载数据」→「股票名称」里指定一个 stock_name.db（可新建一个空文件）。");
            return 0;
        }

        // 1. 配置：线路（默认走配置，失败后的临时切换由外层传入）
        EasyQuotConfig config = EasyQuotConfig.getInstance();
        String dataSource = sourceOverride == null || sourceOverride.trim().isEmpty()
                ? config.dataSource() : sourceOverride.trim();

        // 2. 输出文件（中间产物，导入库后不再参与取名）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputFile = CSV_DIR + "/stock_names_" + timestamp + ".csv";
        System.out.println("名称 CSV: " + outputFile);

        // 3. 股票代码来源：当前生效的股票数据目录（可能是挂载到外部的）
        String stockDir = resolveStockDir();
        if (stockDir.isEmpty()) {
            throw new Exception(MountConfig.NO_STOCK_DATA_MESSAGE);
        }
        File dbDir = new File(stockDir);
        System.out.println("股票目录: " + dbDir.getAbsolutePath());
        if (!dbDir.isDirectory()) {
            throw new Exception("股票数据目录不存在: " + stockDir
                    + "\n请先在「挂载数据」里重新挂载股票数据");
        }
        List<String> stockCodes = scanStockCodes(dbDir);
        if (stockCodes.isEmpty()) {
            throw new Exception("股票目录里没有 .db 文件: " + stockDir);
        }
        out.accept("找到 " + stockCodes.size() + " 个股票代码");

        // 4. 拉行情（CSV 的第一列代码、第二列名称）
        out.accept("正在获取行情数据...");
        RealtimeQuoteFetcher.fetchToCsv(stockDir, dataSource, outputFile, out);
        out.accept("数据已生成: " + outputFile);

        // 5. 解析 CSV 并写入名称库
        List<StockInfoDAO.StockInfo> stockInfos = parseCSVFile(outputFile);
        out.accept("解析完成，共 " + stockInfos.size() + " 条记录");
        if (stockInfos.isEmpty()) {
            throw new Exception("CSV 解析结果为空，没有可写入的名称");
        }

        StockInfoDAO dao = new StockInfoDAO();
        dao.batchUpsertStockInfo(stockInfos);
        java.io.File nameDb = StockNameStore.dbFile();
        System.out.println("名称库写入完成: "
                + (nameDb == null ? "(未挂载)" : nameDb.getAbsolutePath())
                + "（" + stockInfos.size() + " 条）");
        out.accept("股票名称更新完成，共 " + stockInfos.size() + " 条记录");
        return stockInfos.size();
    }

    /** 当前生效的股票数据目录：未挂载返回空串，不回落到任何默认目录 */
    private static String resolveStockDir() {
        String dir = MountConfig.currentDbDir();
        return dir == null ? "" : dir.trim();
    }

    /** 扫目录里的 .db 文件名当股票代码 */
    private static List<String> scanStockCodes(File dbDir) {
        List<String> codes = new ArrayList<>();
        File[] dbFiles = dbDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".db"));
        if (dbFiles == null) {
            return codes;
        }
        for (File dbFile : dbFiles) {
            String fileName = dbFile.getName();
            int dot = fileName.lastIndexOf('.');
            String code = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (!code.trim().isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * 更新股票名称（带进度框，主升浪画面菜单用）
     *
     * @param parentFrame 父窗口，用于显示进度对话框
     */
    public void updateStockNames(JFrame parentFrame) {
        ProgressDialog progressDialog = new ProgressDialog(parentFrame, "正在更新股票名称...");
        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            private String errorMessage;
            private int updatedCount;

            @Override
            protected Integer doInBackground() {
                try {
                    updatedCount = runUpdate(this::publish);
                    return updatedCount;
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    System.err.println("更新股票名称失败: " + errorMessage);
                    e.printStackTrace();
                    Log.error("更新股票名称失败: " + e.getMessage(), e);
                    return 0;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    progressDialog.updateMessage(message);
                }
            }

            @Override
            protected void done() {
                progressDialog.setCompleted();
                try {
                    if (errorMessage == null || errorMessage.isEmpty()) {
                        JOptionPane.showMessageDialog(parentFrame,
                                "✅ 股票名称更新成功！\n\n"
                                        + "更新数量: " + updatedCount + " 条记录\n"
                                        + "名称库: " + StockNameStore.dbFile().getAbsolutePath() + "\n\n"
                                        + "名称缓存要重启后才会刷新。",
                                "更新成功", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(parentFrame,
                                "❌ 股票名称更新失败！\n\n错误信息: " + errorMessage,
                                "更新失败", JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    progressDialog.dispose();
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true);
    }

    /**
     * 控制台 / StockDataUpdater 入口（无界面，日志进 System.out）。
     *
     * <p>注意<b>不要</b> System.exit：更新器是在自己的线程里反射调用这个 main 的，
     * 退出会把整个更新器画面关掉。</p>
     */
    public static void main(String[] args) {
        try {
            int count = runUpdate(System.out::println);
            if (count <= 0) {
                // 未挂载名称库时 runUpdate 已经打过提示，这里不再假装“完成”
                return;
            }
            System.out.println("=== 股票名称更新完成，共 " + count + " 条 ===");
            java.io.File nameDb = StockNameStore.dbFile();
            System.out.println("名称库: " + (nameDb == null ? "(未挂载)" : nameDb.getAbsolutePath()));
            System.out.println("提示: 名称缓存需要重启程序后刷新。");
        } catch (Exception e) {
            System.out.println("=== 股票名称更新失败 ===");
            System.out.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 解析 CSV：第 1 列代码、第 2 列名称，其余列（现价/成交量等）忽略。
     */
    private static List<StockInfoDAO.StockInfo> parseCSVFile(String csvFilePath) throws IOException {
        List<StockInfoDAO.StockInfo> stockInfos = new ArrayList<>();

        Path path = Paths.get(csvFilePath);
        if (!Files.exists(path)) {
            throw new IOException("CSV文件不存在: " + csvFilePath);
        }
        System.out.println("解析 CSV: " + csvFilePath + "（" + Files.size(path) + " 字节）");

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1) {
                    System.out.println("CSV 表头: " + line);
                    continue;
                }
                // 编码可能不同，字段名不可信，按位置取：第 1 列代码、第 2 列名称
                String[] fields = line.split(",", -1);
                if (fields.length < 2) {
                    continue;
                }
                String stockCode = fields[0].trim();
                String stockName = fields[1].trim();
                if (stockCode.isEmpty() || stockName.isEmpty()) {
                    continue;
                }
                StockInfoDAO.StockInfo stockInfo = new StockInfoDAO.StockInfo();
                stockInfo.setStockCode(stockCode);
                stockInfo.setStockName(stockName);
                stockInfo.setDataSource("easyquotation");
                stockInfos.add(stockInfo);
            }
            System.out.println("CSV 总行数: " + lineNumber + "，有效记录: " + stockInfos.size());
        }
        return stockInfos;
    }

    /** 进度对话框 */
    private static class ProgressDialog extends JDialog {
        private final JLabel messageLabel;
        private final JProgressBar progressBar;

        ProgressDialog(JFrame parent, String initialMessage) {
            super(parent, "更新股票名称", true);
            setSize(400, 150);
            setLocationRelativeTo(parent);
            setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            setResizable(false);

            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            messageLabel = new JLabel(initialMessage);
            messageLabel.setHorizontalAlignment(SwingConstants.CENTER);

            progressBar = new JProgressBar();
            progressBar.setIndeterminate(true);

            panel.add(messageLabel, BorderLayout.CENTER);
            panel.add(progressBar, BorderLayout.SOUTH);
            add(panel);
        }

        void updateMessage(String message) {
            messageLabel.setText(message);
        }

        void setCompleted() {
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("完成");
            progressBar.setStringPainted(true);
        }
    }
}
