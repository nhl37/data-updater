package updater.ui;

/** 程序名和版本号，关于框与窗口标题都从这儿取。 */
public final class UpdaterInfo {

    public static final String NAME = "StockDataUpdater";
    public static final String NAME_CN = "股票数据更新";
    public static final String TAGLINE = "把行情、财报、股东数据弄到本地，随手就能用";
    public static final String SLOGAN = "自己用的一个数据更新小工具";
    public static final String VERSION = "1.0.0";

    private UpdaterInfo() {
    }

    public static String windowTitle() {
        return NAME_CN;
    }
}
