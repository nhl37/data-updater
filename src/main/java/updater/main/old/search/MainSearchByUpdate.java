package updater.main.old.search;

import updater.fetchdata.LatestPriceUpdater;
import updater.fetchdata.RealtimeQuoteFetcher;
import updater.log.Log;

/**
 * 「更新数据-补最新价格」的控制台/反射入口。
 *
 * <p>以前这里自己拼「挂载目录 + 抓行情 + 写库」三步，本地还没有 .db 时会被
 * {@link RealtimeQuoteFetcher} 直接判成「股票目录里没有 .db 文件」，而
 * {@code DbDataUpdater} 只会写已经存在的库 —— 全新机器点了等于没点。
 * 逻辑现在挪到 {@link LatestPriceUpdater}（含「没有库就建库」），这里只保留入口，
 * 因为 StockDataUpdater 是「反射调用 main」的。</p>
 */
public class MainSearchByUpdate {
    public static void main(String[] args) throws Exception {
        Log.info("MainSearchByUpdate-start");
        LatestPriceUpdater.Result result = LatestPriceUpdater.run(System.out::println);
        Log.info("MainSearchByUpdate-end, 入库 " + result.writtenStocks + " 只"
                + (result.createdStocks > 0 ? "，新建库 " + result.createdStocks + " 个" : ""));
    }
}
