package updater.fetchdata.dzh;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;
import updater.util.FileTool;
import updater.util.StockCode;
import updater.db.DBException;
import updater.log.Log;
import updater.util.DividendIOWriter;
import updater.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 解析大智慧的 {@code INFOEX.DAT} 除权除息文件，按市场写入本地库。
 *
 * <p>文件没有索引，只能顺序扫：先在字节流里定位股票代码的起始偏移，
 * 再按记录长度逐条读取。每条记录前 20 字节是日期与文本，日期按
 * <b>小端 int 当 Unix 秒</b>解析；读不到合法日期或撞到下一个代码即认为该股结束。</p>
 */
public class DzhINFOEXDividend {
    private static final String fileName = "INFOEX.DAT";

    private static String getPath() throws ConfigException {
        String dbpath = updater.mount.MountConfig.currentDbDir();
        if (dbpath.isEmpty()) {
            throw new ConfigException(updater.mount.MountConfig.NO_STOCK_DATA_MESSAGE);
        }
        return dbpath;
    }
    public static void loadSZ0() throws IOException, ConfigException, ParseException, DBException, SQLException {
        String path = getPath();
        List<String> fileList = FileTool.searchFilesByPrefix(path, "sz0");
        loadData("dzh.dividend.path.sz.INFOEX", fileList);
    }
    public static void loadSZ3() throws IOException, ConfigException, ParseException, DBException, SQLException {
        String path = getPath();
        List<String> fileList = FileTool.searchFilesByPrefix(path, "sz3");

        loadData("dzh.dividend.path.sz.INFOEX", fileList);
    }

    public static void loadSH() throws IOException, ConfigException, ParseException, DBException, SQLException {
        String path = getPath();
        List<String> fileList = FileTool.searchFilesByPrefix(path, "sh6");

        loadData("dzh.dividend.path.sh.INFOEX", fileList);
    }


    private static void loadData(String subPath,  List<String> fileList) throws DBException, SQLException, ConfigException, IOException, ParseException {
        EnhancedConfigReader reader = AppConfigManager.getInstance().getConfigReader();
        String dzhpath = reader.getString("dzh.path");
        String szpath = reader.getString(subPath);
        String filePath = dzhpath + szpath + fileName;
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("文件不存在: " + filePath);
            return;
        }
        byte[] fileData = Files.readAllBytes(Paths.get(filePath));

        List<String> codeList = new ArrayList<>();
        for(String filename:fileList) {
            String code = filename.substring(2, 2+6);
            codeList.add(code);
        }

        List<String> resultList = new ArrayList<>();
        while(!codeList.isEmpty()) {
            //删除已经检索到的

            //从第一个开始检索
            String startcode = codeList.remove(0);
            int startPos = searchStart(fileData,startcode);//显示整型数据
            //解析数据
            readStockData(fileData, startPos, resultList);

            codeList.removeAll(resultList);
        }



    }





    private static List<String> readStockData(byte[] fileData, int startPos, List<String> stockList ) throws IOException, ConfigException, ParseException {

        int pos12 = 12;
        int pos16 = 16;

        while(true) {
            if(startPos + pos16 + 4>fileData.length) {
                break;
            }
            byte[] codeBytes = Arrays.copyOfRange(fileData, startPos, startPos + 8);
            String code = new String(codeBytes, "GBK").trim();
            if(code.length() != 6) {
                break;
            }

            int value_pos12 = readInt(fileData, startPos + pos12);
            int value_pos16 = readInt(fileData, startPos + pos16);
            int heardOffset;
            if(value_pos12==10 || value_pos12 ==2) {//判断无数据
                int datelen = 44;
                int nextCodePos =startPos + datelen;
                byte[] nextcodeBytes = Arrays.copyOfRange(fileData, nextCodePos, nextCodePos + 8);
                String nextcode = new String(nextcodeBytes, "GBK").trim();
                if(isCode(nextcode)) {//下一个数据
                    startPos = startPos + datelen;
                    continue;
                }

            }

            //有数据
            if(value_pos16 == 16256
                 || value_pos16 == 0) {
                heardOffset = 44;
            }  else {
                heardOffset = 256;
            }

            List<String[]> recordList = new ArrayList<String[]>();

            startPos = readRecord(fileData, startPos, heardOffset, recordList);
//            System.out.println(" stock-start "+ code +" offset:" + startPos + " size:" + recordList.size());
//            System.out.println("stockcode:" + code);
            if(StockCode.isSz(code)
                    || StockCode.isSh(code)
                    || StockCode.isBj(code)) {
                DividendIOWriter.writeDividend(code, "dzh", recordList);
                stockList.add(code);
            }

        }
        Log.info("DzhINFOEXReader stock size:" + stockList.size());
        return  stockList;
    }

    private static boolean isCode(String code) {
        return code.length() == 6 && code.matches("^[0-9]{6}$");
    }

    private static int readInt(byte[] fileData, int offset) {
        ByteBuffer dateBuffer = ByteBuffer.wrap(fileData, offset, 4);
        dateBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int dateInt = dateBuffer.getInt();
        return dateInt;
    }

    private static Date readDate(byte[] fileData, int offset) {
        ByteBuffer dateBuffer = ByteBuffer.wrap(fileData, offset, 4);
        dateBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int dateInt = dateBuffer.getInt();
        Date date = new Date(dateInt * 1000L);
        return date;
    }

    private static int readRecord(byte[] fileData, int startPos, int offset, List<String[]> resultList) throws UnsupportedEncodingException {
        int recordLen = 120;
        int dateoffset = 0;
        int textoffset = dateoffset + 20;

        for (int i=0;;i++){
            int recordStartPos = i*recordLen + startPos + offset;
            if(recordStartPos + 6 > fileData.length) {
                return recordStartPos;
            }
            byte[] startBytes = Arrays.copyOfRange(fileData, recordStartPos, recordStartPos + 6);
            String toCode = new String(startBytes, "GBK").trim();
            if(isCode(toCode)) {
               return recordStartPos;
            }
            Date date = readDate(fileData, recordStartPos + dateoffset);
            if(!TimeUtil.isValid(date)) {
                System.out.println(" stock-end offset:"+recordStartPos);
                return recordStartPos;
            }
            String dateStr = TimeUtil.getDateStr(date);
            int textPos = recordStartPos + textoffset;
            byte[] codeBytes = Arrays.copyOfRange(fileData, textPos, textPos + 64);

            String text = new String(codeBytes, "GBK").trim();
//            System.out.println(" offset:"+textPos+"  text:" + text);
            String[] data = new String[2];
            data[0] = text;
            data[1] = dateStr;
            resultList.add(data);
        }
    }

    /** 6 字节区间全为数字、空格或 0，才当作股票代码看待 */
    private static boolean isLikelyStockCode(byte[] data, int offset) {
        for (int i = 0; i < 6; i++) {
            byte b = data[offset + i];
            if (!((b >= '0' && b <= '9') || b == ' ' || b == 0)) {
                return false;
            }
        }
        return true;
    }

    private static int searchStart(byte[] data, String pCode) {
        int length = data.length;

        for (int offset = 0; offset< length; offset++) {

            if (offset + 6 <= data.length && isLikelyStockCode(data, offset)) {

                String code = new String(data, offset, 6).trim();
                if(code.length()>=6 && code.equals(pCode)) {
                    System.out.printf(" 位置%d  个股票代码 %s ", offset, code);
                    return offset;
                }

            }
        }

        throw new RuntimeException("没有发现起始位置");
    }

}
