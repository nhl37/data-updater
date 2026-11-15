package updater.db;

import java.sql.Connection;

public class BaseTableOperater {
//    public static final int TYPE_DAY = 8;
//    public static final int TYPE_WEEK = 9;
//    public static final int TYPE_MONTH = 10;
    protected Connection conn;
    public void setConn(Connection conn) {
        this.conn = conn;
    }


}
