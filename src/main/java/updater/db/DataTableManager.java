package updater.db;

import updater.cfg.ConfigException;
import updater.fetchdata.tdx.kdata.TdxStockData;

import java.io.IOException;
import java.sql.*;
import java.util.List;

public class DataTableManager extends BaseTableOperater {

    /** 空值哨兵：更新器侧只需这一个常量，不引入调用方的脚本求值包 */
    private static final int NO_DATA = -99;

//    private static DataTableManager dateDTable = null;



    private  void createDataTableExecute(String tablename) throws DBException {
        Statement stmt = null;

        String sql = "CREATE TABLE IF NOT EXISTS " + tablename +
                "(TIME  BIGINT PRIMARY KEY    NOT NULL," +
                " OPEN  INT    NOT NULL, " +
                " CLOSE INT    NOT NULL, " +
                " HIGH  INT     NOT NULL, " +
                " LOW   INT     NOT NULL," +
                "VOL BIGINT NOT NULL," +
                "AMO BIGINT NOT NULL)";

        DBConnection.execute(conn, sql);


    }
    public  void createDataTable(String code) throws DBException {
        createDataTableExecute("T_D_Day");
      //  createDataTableExecute("T_D_Week");
      //  createDataTableExecute("T_D_Month");
        String divSql = DivdendTableManager.getCreateSql();
        DBConnection.execute(conn, divSql);
    }

    /**
     * 保证日线表和分红表都在。
     *
     * <p>盘后下载每只股票都要调一次。已有库（正常情况）先用 {@code sqlite_master} 探一下，
     * 两张表都在就<b>完全不执行 DDL</b> —— {@code CREATE TABLE IF NOT EXISTS} 虽然是空操作，
     * 但每条 SQL 都要 prepare + 解析 + 走一遍错误路径，全市场几千只叠加起来不值当。</p>
     *
     * <p>新建的库、以及用户手工建过的空壳库（有文件没表），才真正去建表。</p>
     *
     * @param existed 打开之前这个 .db 文件是否已经存在
     */
    public void ensureDataTable(String code, boolean existed) throws DBException {
        if (existed && hasTable("T_D_Day") && hasTable(DivdendTableManager.tableName())) {
            return;
        }
        createDataTable(code);
    }

    /** 表是否存在（只读查询，不产生事务、不落盘） */
    private boolean hasTable(String name) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?");
            ps.setString(1, name);
            rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            // 探不出来就当没有，走建表 —— CREATE TABLE IF NOT EXISTS 本身是幂等的
            return false;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响结果
                }
            }
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // 同上
                }
            }
        }
    }
    //    TdxStockData
    public  void insertOrUpdate(TdxStockData tdxdata, int ktype) throws DBException {

//        String  tableName = "T_D_Day";


        insertOrUpdate(tdxdata.getDate(),
                toCent(tdxdata.getOpen()),
                toCent(tdxdata.getClose()),
                toCent(tdxdata.getHigh()),
                toCent(tdxdata.getLow()),
                tdxdata.getVolume(),
                (long)tdxdata.getAmount()
        );
    }

    /**
     * 元 → 分。
     *
     * <p>本地 .day 文件里本来就是分，读出来除以 100 变成 float，写回时再乘 100。
     * 直接 {@code (int)(x*100)} 截断会把 0.53 存成 52（float 乘出来是 52.999996），
     * 实测约 6.5% 的价格会低一分；这里四舍五入，和盘中行情的
     * {@code updater.fetchdata.DbDataUpdater} 口径一致。</p>
     */
    private static int toCent(float price) {
        return Math.round(price * 100f);
    }

    /**
     * 批量写入日线（补充历史记录用）。
     *
     * <p>{@link #insertOrUpdate(TdxStockData, int)} 每条要现拼一次 SQL 再编译，
     * 补全市场 5~10 年历史（千万级行）会慢到不可接受；这里用预编译 + 批处理。
     * <b>事务由调用方负责</b>（{@code setAutoCommit(false)} / {@code commit()}），
     * 这样一只股票的几千行只落一次盘。</p>
     *
     * @return 提交成功的行数
     */
    public  int insertBatch(List<TdxStockData> list) throws DBException {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        final String sql = "INSERT OR REPLACE INTO T_D_Day (TIME,OPEN,CLOSE,HIGH,LOW,VOL,AMO)"
                + " VALUES (?,?,?,?,?,?,?)";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            for (TdxStockData data : list) {
                ps.setLong(1, data.getDate());
                ps.setInt(2, toCent(data.getOpen()));
                ps.setInt(3, toCent(data.getClose()));
                ps.setInt(4, toCent(data.getHigh()));
                ps.setInt(5, toCent(data.getLow()));
                ps.setLong(6, data.getVolume());
                ps.setLong(7, (long) data.getAmount());
                ps.addBatch();
            }
            ps.executeBatch();
            return list.size();
        } catch (SQLException e) {
            throw new DBException("批量写入日线失败: " + e.getMessage(), e);
        } finally {
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响已经提交的数据
                }
            }
        }
    }



    public  void insertOrUpdate(long time, int open, int close, int high, int low, long vol, long amo) throws DBException {

        String sql = "INSERT OR REPLACE  INTO T_D_Day (TIME,OPEN,CLOSE,HIGH,LOW,VOL,AMO) " +
                "VALUES ("
                + time + ", '"
                + open + "', '"
                + close + "', '"
                + high + "','"
                + low + "','"
                + vol + "','"
                + amo + "')";
//        DB.prepareExecute(conn, sql);
        DBConnection.execute(conn,sql);
    }

    public float queryPreDayPrice(int date) throws SQLException, DBException {
        String sql = "SELECT close FROM T_D_Day WHERE time <" + date + " order by time desc limit 1";

        return queryDayPriceBysql(sql);
    }

    public float queryDayPrice(int date) throws SQLException, DBException {
        String sql = "SELECT close FROM T_D_Day WHERE time =" +date;

        return queryDayPriceBysql(sql);
    }

    private float queryDayPriceBysql(String sql) throws SQLException, DBException {

        Statement stmt = null;
        ResultSet rs = null;
        float price = 0;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);
            if (rs.next()) {
                price = rs.getFloat("close");
            }
            return price/100;
        } catch (SQLException e) {
            throw new DBException(e);
        } finally {
            try {
                if(rs != null) rs.close();
                if(stmt != null) stmt.close();

            } catch (SQLException e) {
                throw new DBException(e);
            }
        }
    }


    public  String queryColumns(int kCycle,Integer[] macloseArr, Integer[] maAmoArr) throws ConfigException, IOException {


        // SQL 全部内置在 SqlText：安装包里没有 sql 目录，按相对路径读文件会直接失败
        StringBuffer sql = new StringBuffer(SqlText.forCycle(kCycle));

        sql.append("select time,open,close,high,low,vol,amo  \n");
        for(Integer maCl:macloseArr) {
            //maCl 是设置的均线数量
            sql.append(",CASE WHEN row_num >= " +maCl+ " THEN avg(close) OVER (ORDER BY time desc ROWS BETWEEN ")
                    .append(" CURRENT ROW AND "+ (maCl - 1) +" FOLLOWING) ELSE "+ NO_DATA +" END ")
                    .append(" as MA").append(maCl).append("\n");
//                    .append(maCl - 1)
//                    .append(" PRECEDING AND CURRENT ROW) as MA").append(maCl);
        }
        for(Integer maAmo:maAmoArr) {
            //maAmo 是设置的 成交额平均线
            sql.append(",CASE WHEN row_num >= "+ maAmo +" THEN avg(amo) OVER (ORDER BY time desc ROWS BETWEEN ")
                    .append(" CURRENT ROW AND "+ (maAmo - 1) +" FOLLOWING) ELSE "+ NO_DATA +" END ")
                    .append(" as AMO").append(maAmo).append("\n");
//                    .append(maAmo - 1)
//                    .append(" PRECEDING AND CURRENT ROW) as AMO").append(maAmo);
        }
        String tablename;
        switch (kCycle) {
            case DBConnection.CYCLE_DAY->tablename = "t_day";//参照table_day.sql
            case DBConnection.CYCLE_MONTH->tablename = "t_month";//参照 table_month.sql
            case DBConnection.CYCLE_WEEK->tablename = "t_week";//参照 table_week.sql
            case DBConnection.CYCLE_QUARTER->tablename = "t_quarter";

            default-> throw new RuntimeException("TODO");
        }

        sql.append(" from ").append(tablename).append("\n");
        return sql.toString();
    }

}
