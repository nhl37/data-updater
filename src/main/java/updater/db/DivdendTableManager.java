package updater.db;

public class DivdendTableManager extends BaseTableOperater {
    private static final String tableName = "T_Divdend";

    /** 表名（盘后下载要探「表在不在」，不想在别处硬编码一遍） */
    public static String tableName() {
        return tableName;
    }

    public static String getCreateSql() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName +
                "(TIME   BIGINT PRIMARY KEY    NOT NULL," +
                " FACTOR  FLOAT    NOT NULL, " +
                " TEXT CHAR(64)    NOT NULL)";
        return sql;
    }

    public  void createTable() throws DBException {



        DBConnection.execute(conn, getCreateSql());

    }


    public  void bakupTable() throws DBException {
        String sql = "DROP TABLE IF EXISTS " + tableName + "bakup ";//删除备份表
        DBConnection.execute(conn, sql);

         sql = "ALTER TABLE "+tableName +" RENAME TO " + tableName + "bakup";//备份新数据
        DBConnection.execute(conn, sql);

    }

    public  void insertData(long time, float factor, String TEXT) throws DBException {

        String sql = "INSERT OR REPLACE INTO "+tableName+" (TIME,FACTOR,TEXT) " +
                "VALUES ("
                + time +   ", "
                + factor + ", '"
                + TEXT + "')";
        DBConnection.prepareExecute(conn, sql);
    }
}
