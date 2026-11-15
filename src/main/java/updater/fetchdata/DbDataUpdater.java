package updater.fetchdata;

import updater.cfg.ConfigException;
import updater.db.DBConnection;
import updater.db.DBException;
import updater.db.DataTableManager;
import updater.log.Log;
import updater.util.TimeUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

public class DbDataUpdater {

    /** 一次导入的结果：读了多少行、写了多少只、新建了多少个库 */
    public static class Result {
        public int readRows;
        public int writtenStocks;
        public int createdStocks;
    }

    /**
     * 把行情 CSV 里的最新价写进本地库。
     *
     * <p>库里没有这只股票时<b>顺手建库</b>（建 T_D_Day + 除权表），这样全新安装、
     * 一个 .db 都没有的机器也能靠「补最新价格」把整个股票库建起来，
     * 而不是静默跳过、最后提示「一条都没写」。新建的库只有当天这一根日K，
     * 历史要靠「补充历史记录」从本地数据来源补。</p>
     */
    public static Result updatePriceByFile() throws IOException, ConfigException, DBException {
        return updatePriceByFile(updater.util.ItemProgress.NONE, null, null);
    }

    /**
     * 带逐只台账的版本：每写完一只就往 {@code progress} 报一次，
     * 中断 / 失败的行会留在更新画面的台账里。
     *
     * @param progress  逐只进度，null 当作不需要
     * @param cancel    停止标志，可为 null
     * @param onlyCodes 只处理这些代码（续跑 / 重试失败用），null = 不限
     */
    public static Result updatePriceByFile(updater.util.ItemProgress progress,
                                           java.util.concurrent.atomic.AtomicBoolean cancel,
                                           java.util.Collection<String> onlyCodes)
            throws IOException, ConfigException, DBException {
        updater.util.ItemProgress sink = progress == null
                ? updater.util.ItemProgress.NONE : progress;
        EasyQuotConfig config = EasyQuotConfig.getInstance();
        String priceListFile = config.priceListOutputFile();
        String line;
        boolean isFirst = true;
        int i =0;
        int written = 0;
        int created = 0;
        long starttime = System.currentTimeMillis();
        // 行情 CSV 由 RealtimeQuoteFetcher 写成 UTF-8（带 BOM），不能跟着系统默认编码走
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(priceListFile), StandardCharsets.UTF_8))) {
            while ((line = br.readLine()) != null) {
                if(isFirst) {
                    isFirst = false;
                    continue;
                }

                String[] columns = line.split(",");
                String code = columns[0];
                // 续跑 / 重试失败：只处理清单里那几只
                if (onlyCodes != null && !onlyCodes.contains(code)) {
                    continue;
                }
                if (cancel != null && cancel.get()) {
                    break;
                }
                sink.begin(code);
                try {
                    // 原来用 float 再 (int) 截断，10.23 这种会算成 1022，高价/低价差一分
                    int close = (int) Math.round(Double.parseDouble(columns[2]) * 100);
                    int open = (int) Math.round(Double.parseDouble(columns[3]) * 100);
                    int high = (int) Math.round(Double.parseDouble(columns[4]) * 100);
                    int low = (int) Math.round(Double.parseDouble(columns[5]) * 100);
                    long vol = (long) Double.parseDouble(columns[6]);
                    long amo = (long) Double.parseDouble(columns[7]);
                    int time = TimeUtil.getWorkYyyyMMdd();

                    // 停牌 / 没成交：这类行写进去只会污染K线，跳过并说清原因
                    if (close == 0 || vol == 0) {
                        sink.skip(code, close == 0 ? "停牌或无成交（收盘价 0）" : "停牌或无成交（成交量 0）");
                        continue;
                    }
                    if (open == 0) open = close;
                    if (high == 0) high = close;
                    if (low == 0) low = close;
                    if (amo == 0) amo = vol * close;

                    String dbFilename = DBConnection.dbFilePath(code);
                    File dbfile = new File(dbFilename);
                    boolean exists = dbfile.isFile();

                    Connection conn = DBConnection.connect(code, true);
                    try {
                        DataTableManager dataTable = new DataTableManager();
                        dataTable.setConn(conn);
                        if (!exists) {
                            // 库文件刚被创建出来：没有表就不能写
                            dataTable.createDataTable(code);
                            created++;
                        }
                        dataTable.insertOrUpdate(
                                time, open, close, high, low, vol, amo);
                    } finally {
                        // 全市场几千只，每只都必须关掉自己开的连接，否则连接和它的页缓存会一直累积
                        DBConnection.closeQuietly(conn);
                    }
                    written++;
                    i++;
                    sink.done(code, exists ? "更新当日一根" : "新建库 + 当日一根");
                    if (i % 100 == 0) {
                        long endtime = System.currentTimeMillis();
                        Log.info("DbDataUpdater-i:" + i + " code:" + code + " time:" + (endtime - starttime));
                        starttime = endtime;
                    }
                } catch (Throwable t) {
                    // 单只失败不能把整轮抓价停住（库被占用、磁盘满都可能）
                    sink.fail(code, t.getMessage() == null ? t.toString() : t.getMessage());
                    Log.error("写入失败 " + code + ": " + t);
                }
            }
        }
        // 盘中更新写完才记录时间：脚本里的 AMO_TDX/HSL_TDX 靠它判断当天K线走到哪
        if (written > 0) {
            PriceUpdateClock.markUpdated();
            Log.info("DbDataUpdater-end, 记录价格数据更新时间，写入 " + written + " 只"
                    + (created > 0 ? "（其中新建库 " + created + " 个）" : ""));
        } else {
            Log.info("DbDataUpdater-end, 没有写入任何价格数据，不更新记录时间");
        }

        Result result = new Result();
        result.readRows = i;
        result.writtenStocks = written;
        result.createdStocks = created;
        return result;
    }

}
