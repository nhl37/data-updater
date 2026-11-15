package updater.fetchdata;

import updater.log.Log;
import updater.mount.MountConfig;

import java.io.File;
import java.util.function.Consumer;

/**
 * 「补最新价格」：从行情接口拉全市场最新价，写进本地个股库。
 *
 * <p>原来这条链路（{@code MainSearchByUpdate}）只会写「已经存在的」库文件：
 * 一个 .db 都没有的全新机器点下去只会被静默跳过，最后什么也没发生。
 * 现在补上两条：</p>
 * <ul>
 *   <li>本地没有库时，按 {@link StockCodeSource} 给出的代码清单（名称库 / 本地数据来源的 vipdoc）
 *       逐只建库，建出来的库只含当天这一根日K</li>
 *   <li>单只股票库不存在时由 {@link DbDataUpdater} 顺手建，不再跳过</li>
 * </ul>
 *
 * <p>历史数据不在这里补：建库当天只有一根K线，请随后执行「补充历史记录」从本地数据来源补 5~10 年日线。</p>
 */
public final class LatestPriceUpdater {

    /** 一次「抓最新价」的结果 */
    public static class Result {
        /** 请求了多少只股票 */
        public int codeCount;
        /** 行情文件里写入了多少条 */
        public int quoteRows;
        /** 写进库的股票只数 */
        public int writtenStocks;
        /** 本次新建的库文件数（0 = 只是增量更新） */
        public int createdStocks;
        /** 代码清单来源说明 */
        public String codeSource = "";
        /** 本地库目录 */
        public String dbDir = "";
        /** true = 这次是把库从无到有建起来 */
        public boolean newLibrary;
    }

    /** 数据文件夹不可用时的统一提示（建库也得先有地方落盘） */
    public static final String NO_DATA_DIR_MESSAGE =
            "数据文件夹不可用，没法写入行情。\n\n"
                    + "请在上方「数据文件夹」里选一个可写的目录"
                    + "（可以选一个空文件夹，本程序会在其中建立 db 子目录和数据库）。";

    /** 没有任何可用代码来源时的提示 */
    public static final String NO_CODE_MESSAGE =
            "本地没有股票数据库，也找不到可用的股票代码清单，无法建库。\n\n"
                    + "任选一种方式提供代码清单：\n"
                    + "  · 在数据文件夹里放一个股票名称库（stock_name.db）\n"
                    + "  · 在「路径设置 → 本地数据来源」里选好目录（代码取自本地日线文件）\n"
                    + "  · 用「盘后数据下载 → 从本地加载」导入已有的 CSV 行情";

    private LatestPriceUpdater() {
    }

    /**
     * 抓最新价并写入本地库（没有库就建）。
     *
     * <p>目录来自 {@link MountConfig#currentDbDir()}：数据更新器里已经由它自己的
     * 「数据文件夹」覆盖，调用方里仍是挂载值。</p>
     *
     * @param log 进度输出，null 表示打到控制台
     * @throws IllegalStateException 数据文件夹不可用 / 没有代码来源（消息可直接给用户看）
     */
    public static Result run(Consumer<String> log) throws Exception {
        return run(log, null, null, null);
    }

    /**
     * 带逐只台账的版本：先按代码清单铺出台账，再逐只写库。
     *
     * @param progress  逐只进度（更新画面传台账），null 表示不需要
     * @param cancel    停止标志，可为 null
     * @param onlyCodes 只处理这些代码（续跑 / 重试），null = 全市场
     */
    public static Result run(Consumer<String> log, updater.util.ItemProgress progress,
                             java.util.concurrent.atomic.AtomicBoolean cancel,
                             java.util.Collection<String> onlyCodes) throws Exception {
        return run(log, progress, cancel, onlyCodes, null);
    }

    /**
     * 指定线路的版本（默认线路失败后的临时切换用，画面上没有对应控件）。
     *
     * @param sourceOverride 本次临时使用的线路 id；
     *                       空或 null 表示跟配置走默认线路
     */
    public static Result run(Consumer<String> log, updater.util.ItemProgress progress,
                             java.util.concurrent.atomic.AtomicBoolean cancel,
                             java.util.Collection<String> onlyCodes,
                             String sourceOverride) throws Exception {
        Consumer<String> out = log == null ? System.out::println : log;
        updater.util.ItemProgress sink = progress == null
                ? updater.util.ItemProgress.NONE : progress;

        String dbDir = MountConfig.currentDbDir();
        if (dbDir.isEmpty()) {
            throw new IllegalStateException(NO_DATA_DIR_MESSAGE);
        }
        File dir = new File(dbDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("数据文件夹里的股票库目录不存在，且无法创建: " + dir.getAbsolutePath()
                    + "\n请检查该路径是否可写。");
        }

        StockCodeSource.Result source = StockCodeSource.resolve(dir);
        if (source.isEmpty()) {
            throw new IllegalStateException(NO_CODE_MESSAGE);
        }

        Result result = new Result();
        result.dbDir = dir.getAbsolutePath();
        result.codeCount = source.codes.size();
        result.codeSource = source.describe();
        result.newLibrary = source.createsNewLibrary();

        if (result.newLibrary) {
            out.accept("本地还没有股票数据库，本次将新建 " + result.codeCount
                    + " 个股票库（代码来源 → " + source.describe() + "）");
            out.accept("新建的库只有今天这一根日K，历史数据请随后用「更新数据-补充历史记录」补齐");
        } else {
            out.accept("股票代码清单: " + result.codeCount + " 只（" + source.describe() + "）");
        }

        EasyQuotConfig config = EasyQuotConfig.getInstance();
        // 临时切换的线路优先，没切就走配置里的默认线路
        String dataSource = sourceOverride == null || sourceOverride.trim().isEmpty()
                ? config.dataSource() : sourceOverride.trim();
        String outputFile = config.priceListOutputFile();

        // 台账先铺出来：用户一眼能看到「这一轮要做多少只」，中断后也知道还剩谁
        java.util.List<String> planned = new java.util.ArrayList<String>();
        for (String code : source.codes) {
            if (onlyCodes == null || onlyCodes.contains(code)) {
                planned.add(code);
            }
        }
        sink.planned(planned);

        result.quoteRows = RealtimeQuoteFetcher.fetchToCsv(dir, dataSource, outputFile,
                source.codes, out);
        DbDataUpdater.Result write = DbDataUpdater.updatePriceByFile(sink, cancel, onlyCodes);
        result.writtenStocks = write.writtenStocks;
        result.createdStocks = write.createdStocks;

        String summary = "最新价格写入完成：请求 " + result.codeCount + " 只，行情 " + result.quoteRows
                + " 条，入库 " + result.writtenStocks + " 只"
                + (result.createdStocks > 0 ? "（新建库 " + result.createdStocks + " 个）" : "");
        out.accept(summary);
        Log.info(summary);
        return result;
    }
}
