package updater.main.down;

import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.log.Log;

/**
 * 「更新数据-补充历史记录」的控制台入口（给 StockDataUpdater 的菜单/参数用）。
 *
 * <p>做的事就是把本地数据来源的 vipdoc 里的全部历史日线并进本地库（只补本地没有的日期，
 * 本地没有库就建），具体逻辑在 {@link ReaderFromTdx#importAllHistory}。图形界面走同一个方法，
 * 只是多一个结论框。</p>
 */
public class MainBackfillHistoryFromTdx {

    public static void main(String[] args) throws Exception {
        Log.info("MainBackfillHistoryFromTdx-start");
        ReaderFromTdx.HistoryResult result = ReaderFromTdx.getInstance()
                .importAllHistory(System.out::println);
        System.out.println("=== 补充历史记录完成 ===");
        System.out.println("个股: " + result.scannedStocks + " 只");
        System.out.println("补入数据: " + result.updatedStocks + " 只 / " + result.rowsInserted + " 条");
        if (result.createdStocks > 0) {
            System.out.println("新建库: " + result.createdStocks + " 个");
        }
        if (result.tdxFirstDate > 0) {
            System.out.println("日期范围: " + result.tdxFirstDate + " ~ " + result.tdxLastDate);
        }
        System.out.println("数据库目录: " + result.dbDir);
        Log.info("MainBackfillHistoryFromTdx-end");
    }
}
