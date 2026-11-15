package updater.fetchdata;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;
import updater.util.StockCode;
import updater.log.Log;
import updater.util.DividendIOWriter;
import updater.util.TimeUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class DividendFileUtl {
    private static List<String> codeList = null;

    public static List<String> getCodeList(boolean isHasPre) throws ConfigException {
        if(codeList == null) {
            codeList = new ArrayList<>();
            String dataPath = updater.mount.MountConfig.currentDbDir();
            if (dataPath.isEmpty()) {
                Log.info("未挂载股票数据，代码清单为空");
                return codeList;
            }
            File path = new File(dataPath);
            File[] files = path.listFiles();
            if (files == null) {
                return codeList;
            }
            for(File file:files) {
                String filename = file.getName();
                if(filename.endsWith(".db")) {
                    String code = filename.substring(0,filename.length()-3);
                    if(!isHasPre) {
                        code = code.substring(2);
                    }
                    codeList.add(code);
                }
            }
        }
        return codeList;
    }

    public  static List<String> searchHas(String urlname) {
        File path = new File("data/" + urlname);
        File[] files = path.listFiles();
        List<String> result = new ArrayList<>();
        if(files == null ) {
            return result;
        }
        for(File f : files) {
            String filename = f.getName();
            if(filename.endsWith(urlname)) {
                String code =  filename.substring(0, filename.length() - urlname.length() -1);//要减去.
                result.add(code);
            }
        }
        return result;
    }

    //过滤双引号
    private static String trimDividendText(String str) {
        int start = 0;
        int end = str.length();
        if(str.startsWith("\"")) {
            start = 1;
        }
        if(str.endsWith("\"")) {
            end = str.length() - 1;
        }
        str = str.substring(start, end);
        StringBuilder oldStr = new StringBuilder(str);
        StringBuilder newStr;
//        String leftStr="";
//        String rightStr="";
//        int searchIndex = 0;
        while(true) {//过滤（ 里面可能有,
            newStr = new StringBuilder();
             int leftIndex = oldStr.indexOf("(");
//            searchIndex = leftIndex + 1;
            if(leftIndex == -1) {
                break;
            }
            newStr.append(oldStr, 0, leftIndex);

            int rightIndex = oldStr.indexOf(")", leftIndex);
            if(rightIndex >0
                    && rightIndex <oldStr.length() -1) {
                newStr.append(oldStr, rightIndex + 1, str.length());
            }
            oldStr = newStr;
        }

        return oldStr.toString();

    }

    public  static List<List<DividendData>> getDividends(String urlName, List<String> errList) throws IOException, ConfigException {
        List<String> divCodeList = searchHas(urlName);
        List<String> dbCodeList = getCodeList(false);
        List<List<DividendData>> resultList = new ArrayList<>();
        for(String code:divCodeList){
            if(!dbCodeList.contains(code)) {
                continue;
            }
            String path = "data/" + urlName + "/";
            String filename = path + code+"."+urlName;
            java.io.FileReader fr = new java.io.FileReader(filename);
            BufferedReader br = new BufferedReader(fr);
            String line;
            List<DividendData> lineList = new ArrayList<>();
            while ((line =br.readLine()) != null) {
                DividendData dividendData = new DividendData();


                String[] datas = line.split(",");
                String date = datas[1];
                dividendData.exDividendDate = Integer.parseInt(date);

                if(datas.length<3) {
                    Log.error("########## warn:error data:" + filename);
                    if(!errList.contains(code)) {
                        int curDayTime = TimeUtil.getWorkYyyyMMdd();
                        int year10ago = curDayTime -  110000;//11年前
                        if(dividendData.exDividendDate >= year10ago) {//11年内的错误数据需要重新读取
                            errList.add(code);
                        }
                    }
                    continue;
                }

                String text = trimDividendText(datas[2]);
//                if(code.startsWith(""))
                dividendData.stockCode = StockCode.addPrefix(code);


                dividendData.text = text;

                if(!text.trim().isEmpty()) {
                    DividendTextParser.parse(dividendData, text);
                    lineList.add(dividendData);
                }
            }
            resultList.add(lineList);
        }

        return resultList;
    }

    public static void writeDzhErrorData(List<String> errorList) throws ConfigException, ParseException, IOException {
        String filename = "errorDividend.dzh";
        EnhancedConfigReader config = AppConfigManager.getInstance().getConfigReader();
        String dataPath = config.getString(AppConfigManager.KEY_DataPath);
        String fileAllPath= dataPath + filename;
        DividendIOWriter.writeCodeList(errorList, fileAllPath);
    }
}
