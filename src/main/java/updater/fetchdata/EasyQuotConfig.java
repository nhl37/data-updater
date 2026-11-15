package updater.fetchdata;

import updater.cfg.BundledDefaults;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class EasyQuotConfig {

    /** 接口地址配置；磁盘没有时读 jar 内 {@code /defaults/easyquotation.properties} */
    private static final String CONFIG_FILE = "config/easyquotation.properties";

    /** 配置项缺失时的默认值：和仓库里的 config/easyquotation.properties 保持一致 */
    private static final String DEFAULT_PYTHON_FILE = "easyquotation/easyquotation/read_price.py";
    private static final String DEFAULT_OUTPUT_PREFIX = "data/easyquotation/stock_data_";
    private static final String DEFAULT_DATA_SOURCE = "tencent";

    private static String mOutfile = null;

    public static EasyQuotConfig config = null;
    private String mPriceListPythonFile;
    private String mPriceListOutFilePrefix;
    private String mDataSource;
    private String mSpecialStocks;

    public static EasyQuotConfig getInstance() throws IOException {
        if(config == null) {
            config = new EasyQuotConfig();
            // 磁盘(工作目录/jar 目录/jar 上级) → jar 内 → 代码默认值，三级兜底
            config.mPriceListPythonFile = BundledDefaults.get(CONFIG_FILE,
                    "priceList.pythonFile", DEFAULT_PYTHON_FILE);
            config.mPriceListOutFilePrefix = BundledDefaults.get(CONFIG_FILE,
                    "priceList.outputFile.Prefix", DEFAULT_OUTPUT_PREFIX);
            config.mDataSource = BundledDefaults.get(CONFIG_FILE,
                    "priceList.dataSource", DEFAULT_DATA_SOURCE);
            String special = BundledDefaults.get(CONFIG_FILE, "priceList.specialStocks", "");
            config.mSpecialStocks = special.isEmpty() ? null : special;
        }
        return config;
    }

    public  String priceListPythonFile() {
        return config.mPriceListPythonFile;
    }

    public  String priceListOutputFile( ) {
        if(mOutfile == null) {
            String  dataSource = config.mDataSource;
            String relative = config.mPriceListOutFilePrefix + dataSource +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
            // 落成绝对路径：jar 被放到别处启动时，行情 csv 不该跟着落到启动目录
            mOutfile = updater.cfg.ScriptPaths.workFile(relative).getPath();
        }
        return mOutfile;
    }

    public  String dataSource() {
        return config.mDataSource;//sina, tencent/qq, jsl
    }

    public String specialStocks()  {
        return config.mSpecialStocks;
    }

}
