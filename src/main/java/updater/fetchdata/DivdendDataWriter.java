package updater.fetchdata;

import updater.cfg.ConfigException;
import updater.db.DBConnection;
import updater.db.DBException;
import updater.db.DataTableManager;
import updater.db.DivdendTableManager;
import updater.log.Log;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class DivdendDataWriter {

    /**
     * 重算并写入某只股票的前复权除权表 T_Divdend。
     *
     * 说明:
     *   1) 先备份旧表(T_Divdend -> T_Divdendbakup)，再重建；
     *   2) 按除权日期从新到旧累乘前复权因子；
     *   3) 缺日线(取不到除权日前收盘价)的记录会被跳过并计数，便于定位"写 0 条"。
     */
    public static void insertForwardAdjusted(String code, List<DividendData> dataList)
            throws ConfigException, DBException {

        if (code == null || dataList == null || dataList.isEmpty()) {
            return;
        }

        File dbFile = new File(DBConnection.dbFilePath(code));
        if (!dbFile.exists()) {
            //Log.error("跳过除权写入(无db文件): " + code);
            return;
        }

        Connection conn = null;
        int wrote = 0;
        int noPrice = 0;
        int badFactor = 0;
        try {
            conn = DBConnection.connect(code);
            conn.setAutoCommit(false);

            DivdendTableManager divDendTable = new DivdendTableManager();
            DataTableManager dataTable = new DataTableManager();
            divDendTable.setConn(conn);
            dataTable.setConn(conn);

            try {
                divDendTable.bakupTable();
            } catch (Exception e) {
                Log.out("备份除权表不成功(可能首次创建): " + code);
            }
            divDendTable.createTable();

            // 从新到旧，累乘前复权因子
            dataList.sort((a, b) -> Integer.compare(b.exDividendDate, a.exDividendDate));

            float cumulativeFactor = 1.0f;
            for (DividendData dividend : dataList) {
                float price;
                try {
                    price = dataTable.queryPreDayPrice(dividend.exDividendDate);
                } catch (Exception e) {
                    noPrice++;
                    continue;
                }
                if (price <= 0) {
                    noPrice++;
                    continue;
                }

                float singleFactor = dividend.getFactor(price);
                if (singleFactor <= 0
                        || Float.isNaN(singleFactor)
                        || Float.isInfinite(singleFactor)) {
                    badFactor++;
                    continue;
                }

                cumulativeFactor *= singleFactor;
                divDendTable.insertData(dividend.exDividendDate, cumulativeFactor, dividend.text);
                wrote++;
            }

            conn.commit();
            Log.info("除权写入 " + code
                    + " 写入:" + wrote
                    + " 无前收盘:" + noPrice
                    + " 因子异常:" + badFactor
                    + " 合计:" + dataList.size());
            if (wrote == 0) {
                Log.error("除权写入为0 " + code
                        + " 请检查该股票 T_D_Day 日线数据与除权日期范围");
            }
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception ignore) {
                    // ignore
                }
            }
            Log.error("除权写入失败 " + code + " : " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }
            try {
                DBConnection.close(code);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    /**
     * 批量写入；单只股票失败不影响其它股票。
     */
    public static void insertForwardAdjusted(List<List<DividendData>> dataList)
            throws DBException, SQLException, ConfigException {

        if (dataList == null) {
            return;
        }

        int processed = 0;
        int success = 0;
        for (List<DividendData> dividendDataList : dataList) {
            if (dividendDataList == null || dividendDataList.isEmpty()) {
                continue;
            }
            dividendDataList.sort((a, b) -> Integer.compare(b.exDividendDate, a.exDividendDate));
            String code = dividendDataList.get(0).stockCode;
            try {
                insertForwardAdjusted(code, dividendDataList);
                success++;
            } catch (Exception e) {
                Log.error("除权写入异常 " + code + " : " + e.getMessage());
            }

            processed++;
            if (processed % 100 == 0) {
                Log.info("DivdendDataInsert-" + processed + ":" + code);
            }
        }

        Log.info("DivdendDataInsert-完成 处理:" + processed + " 成功:" + success);
    }

    public void writeDB(String code) {
        // 保留原接口
    }
}
