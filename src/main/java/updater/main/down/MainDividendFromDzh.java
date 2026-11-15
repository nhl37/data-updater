package updater.main.down;
import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;
import updater.fetchdata.DivdendDataWriter;
import updater.fetchdata.DividendData;
import updater.fetchdata.DividendFileUtl;
import updater.db.DBException;
import updater.fetchdata.dzh.DzhINFOEXDividend;
import updater.fetchdata.dzh.DzhPWRDataDividend;
import updater.log.Log;
import updater.util.FileTool;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 除权数据工具类
 */
public class MainDividendFromDzh {

    public static void loadPWR() {
        try {
            // 假设大智慧数据目录为 D:\dzh\data
//            String basePath = "D:/dzh365(64)";

            List<List<DividendData>> allDividends = null;
            DzhPWRDataDividend shParser = DzhPWRDataDividend.createParser();
            EnhancedConfigReader app = AppConfigManager.getInstance().getConfigReader();
            String dzhpath = app.getString("dzh.dividend.path");
            String pwrpath = app.getString("dzh.dividend.path.PWR");
            String path = dzhpath + pwrpath;
            if(!path.endsWith("/")) path=path+"/";
            String filestext = app.getString("dzh.dividend.file");
            String[] filenames = filestext.split(",");
            for(String filename : filenames){
                String filepath = "data/dzh/" + filename;
                if(!new File(filepath).exists()){
                    filepath = path + filename;
                    if(!new File(filepath).exists()){
                        throw new ConfigException("config dividend.dzh("+filename+") is not exists");
                    }
                }
                // 解析除权数据
                allDividends = shParser.parse(filepath);
//            //保存数据
                DivdendDataWriter.insertForwardAdjusted(allDividends);
                Log.info(filename + " 共解析到 " + allDividends.size() + " 条数据");
            }

            if(allDividends != null){
                // 显示前100条记录
                int count = Math.min(100, allDividends.size());
                for (int i = 0; i < count; i++) {
                    System.out.println(allDividends.get(i));
                }
            } else {
                System.out.println("has not dividend data");
            }

            // 示例：筛选特定股票的除权数据

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
    public static void loadINFOEX() throws DBException, SQLException, ConfigException, IOException, ParseException {
        DzhINFOEXDividend.loadSH();
        DzhINFOEXDividend.loadSZ0();
        DzhINFOEXDividend.loadSZ3();

        List<String> errorList = new ArrayList<String>();
        List<List<DividendData>> allDividendList = DividendFileUtl.getDividends("dzh", errorList);
        DividendFileUtl.writeDzhErrorData(errorList);
        DivdendDataWriter.insertForwardAdjusted(allDividendList);



    }

    /**
     * 使用示例
     */
    public static void main(String[] args) throws DBException, SQLException, ConfigException, IOException, ParseException {
        Log.info("MainDividendFromDzh-start");
        EnhancedConfigReader config = AppConfigManager.getInstance().getConfigReader();
        String dataPath = config.getString(AppConfigManager.KEY_DataPath);
        String dzhPath = dataPath + "dzh";
        FileTool.deleteDirectoryNIO(dzhPath);
//        loadPWR();
        loadINFOEX();
        Log.info("MainDividendFromDzh-end");
    }
}
