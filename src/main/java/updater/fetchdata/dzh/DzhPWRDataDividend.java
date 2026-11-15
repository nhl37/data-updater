package updater.fetchdata.dzh;
import updater.fetchdata.DividendData;
import updater.fetchdata.DividendTextParser;
import updater.log.Log;
import com.alibaba.fastjson2.util.DateUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 大智慧除权数据解析器
 * 用于解析大智慧软件中的除权数据文件(如STKINFO.DAT, STKINFO60.DAT等)
 * 大智慧数据文件通常位于DATA/SH/和DATA/SZ/目录下:cite[2]
 */
public class DzhPWRDataDividend {
//    private String filePath;
    private String charsetName;

    public DzhPWRDataDividend(String charsetName) {
//        this.filePath = filePath;
        this.charsetName = charsetName; // 大智慧通常使用GBK编码
    }

    /**
     * 解析除权数据文件
     */
    public List<List<DividendData>> parse(String filePath) throws IOException {
        List<List<DividendData>> dataList = new ArrayList<>();
        List<DividendData> dividendList = null;

        try (RandomAccessFile file = new RandomAccessFile(filePath, "r");
             FileChannel channel = file.getChannel()) {

            // 大智慧数据文件通常使用小端字节序:cite[4]
            // 读取文件头等信息，这里需要根据实际格式调整
            int startPos = 8;
            file.seek(startPos);//起始位置
            int group = 120;
            // 假设文件由多条记录组成，遍历读取
            long i=0;
            long lineStartPos = file.getFilePointer();
            byte[] linebytes = new byte[120];

            DividendData dividendData = null;
            String curstockCode = null;
            while (file.read(linebytes)>0) {
                ByteBuffer lineBuffer = ByteBuffer.wrap(linebytes);
//                bb.order(ByteOrder.LITTLE_ENDIAN);
                if(curstockCode != null){

                    if( curstockCode.startsWith("SH6")
                            || curstockCode.startsWith("SZ0")
                            || curstockCode.startsWith("SZ3"))
                     {//注意这里不需要进行 沪市或深市的 基金
                        //继续
                     } else {//不处理基金
                         continue;
                     }
                }
                try {
                    //120个字节为一段
                    //12  是 股票代码
                    //124 确认性数字
                    // 数字位置 124 128  144
                    //148 位置是 10派4.10
                    //268 10 派3.21
                    //388 10派3.20
                    //508 10派4.10
                    //###### 数字位置（日期）（4个字节）第一条是-1：8 128 248 368
                    //###### 数字位置：24 144 264 384
                    //###### 字符串位置(12个字节):28 148 268 388 508

                    int dateInt = Integer.reverseBytes(lineBuffer.getInt()); // 小端转大端
//                    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//                    Date datetest = formatter.parse("2025-07-16 00:00:00");
//                    long time = datetest.getTime();

                    if(dateInt == -1) {// 新数据
                        byte[] codeBytes = new byte[8];//  读取股票代码 (通常是6字节ASCII字符串)
                        lineBuffer.get(codeBytes);
//                        file.readFully(codeBytes);
                        curstockCode = new String(codeBytes, charsetName).trim();
                        dividendList = new ArrayList<DividendData>();
                        dataList.add(dividendList);
                    } else {//128
                        dividendData =  new DividendData();

                        dividendData.stockCode = curstockCode;//代码

                        Date date = new Date(dateInt*1000L);
                        String dateStr = DateUtils.format(date,"yyyyMMdd");
                        dividendData.exDividendDate = Integer.valueOf(dateStr);//除权时间
                        //###################  非直接数字  #############
                        //132 （送股）10转增3.000股 派2.000元 /10转增1.000股 派5.150元 /10送3.000股 转增7.000股 派2.000元
                        int value2 = Integer.reverseBytes(lineBuffer.getInt());
                        //136 (配股数量) (10配3.000股 配股价8.750元)
                        int value3 = Integer.reverseBytes(lineBuffer.getInt());
                        //140 (配股价格) (10配3.000股 配股价8.750元)
                        int value4 = Integer.reverseBytes(lineBuffer.getInt());
                        //144 （分红）(10派1.800元)
                        int value5 = Integer.reverseBytes(lineBuffer.getInt());
                        //############################################

                        byte[] codeBytes = new byte[64];//  读取股票代码 (通常是6字节ASCII字符串)
//                        file.readFully(codeBytes);
                        lineBuffer.get(codeBytes);
                        String text = new String(codeBytes, charsetName).trim();
                        if(!text.trim().isEmpty()) {
                            dividendData.text = text;

                            DividendTextParser.parse(dividendData, text);
                            dividendList.add(dividendData);
                        }

                        if(Log.outDebugLog) {
                            String message = " stockCode：" + curstockCode +
                                    " date：" + dateStr
                                    + " " + text;
                            Log.out("LoadDzhDividendData:" +  message);
                        }
                    }


                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return dataList;
    }




    /**
     * 解析单条除权记录
     * 大智慧除权数据格式参考：:cite[1]
     * 注意：具体的字节偏移和数据类型需要根据实际文件格式调整
     */

    /**
     * 读取小端字节序的浮点数
     */
    private float readLittleEndianFloat(RandomAccessFile file) throws IOException {
        int intBits = Integer.reverseBytes(file.readInt());
        return Float.intBitsToFloat(intBits);
    }

    /**
     * 根据市场类型创建解析器
     */
    public static DzhPWRDataDividend createParser() {
//        String filePath = String.format("%s/%s/STKINFO60.DAT", baseDataPath, market.toUpperCase());
//        String filepath = "SPLIT.PWR";
        return new DzhPWRDataDividend("GBK");
    }
}
