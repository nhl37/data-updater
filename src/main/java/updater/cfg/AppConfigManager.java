package updater.cfg;

/**
 * 配置管理器 - 单例模式
 * 支持多配置文件管理
 */
public class AppConfigManager {
    public static final String KEY_DataPath= "data.path";
    public static final String KEY_DBPath = "data.path.db";
    public static final String KEY_DBBAKPath = "data.path.dbbak";
    public static final String KEY_TdxPath= "tdx.path";
    /** 均线周期（收盘价口径），键名与主程序一致：{@code moving.average.close} */
    public static final String KEY_MA_CLOSE = "moving.average.close";
    /** 均量周期（成交额口径），键名与主程序一致：{@code moving.average.amo} */
    public static final String KEY_MA_AMO = "moving.average.amo";

    private static AppConfigManager instance;


    //    private Map<String, EnhancedConfigReader> configReaders;
    EnhancedConfigReader configReader;

    public EnhancedConfigReader getConfigReader() {
        return configReader;
    }

    public AppConfigManager() {
//        this.configReaders = new HashMap<>();
    }

    /**
     * 获取单例实例
     */
    public static synchronized AppConfigManager getInstance() throws ConfigException {
        if (instance == null) {
            instance = new AppConfigManager();
            // 加载多个配置文件
            // 走 ProgramHome 拼成绝对路径：运行时改 user.dir 对相对路径无效（JVM 缓存了启动目录），
            // jar 被放到程序目录以外时会读不到配置 —— 除权任务就是这么失败的
            instance.loadConfig(ProgramHome.resolve("config.properties").getPath());
            // 获取特定配置
//            EnhancedConfigReader appConfig = instance.getConfig("config");
        }
        return instance;
    }

    /**
     * 加载配置文件
     *
     * @param filePath 绝对路径，或相对程序文件夹的路径
     */
    public boolean loadConfig(String filePath) throws ConfigException {
        configReader = new EnhancedConfigReader();
        String path = ProgramHome.resolve(filePath).getPath();
        boolean success = configReader.load(path);

        if (success) {
//            configReaders.put(configName, reader);
            System.out.println("成功加载配置文件: "  + path);
        } else {
            throw new ConfigException("加载配置文件失败: "  + path);
        }

        return success;
    }


}
