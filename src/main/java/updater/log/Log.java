package updater.log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Log {
    private static SimpleFileLogger logger = new SimpleFileLogger("logs/custom.log");
    public static final boolean outDebugLog = true;
    public static final boolean outMethodStart = false;
    public static final boolean outKeyValue = false;
    public static final boolean outScritpArgu = false;
    public static final List<String> outCode = null;
//    Arrays.asList(new String[]{
//            "002703",
//            "688630",
//            "600620",
//            "688661",
//            "002985",
//            "688326",
//            "688711",
//            "",
//            });
    public static final boolean outCurIndex = false;
    public static final boolean outJavaFunc = false;
    public static final boolean outCacheData = false;
    public static final boolean outSql = false;

    private static final int LEVEL_ALL = 0;
    public static final int LEVEL_FUNC = 1;
    private static final int LEVEL_LONGTIME_FUNC = 2;
    private static final int LEVEL_CUSTOM = 7;
    private static final int LEVEL_INFO = 8;


    private static final int debugLevel = LEVEL_CUSTOM;//0:all 1:function


//    pulbic static final bollean
    private static Log log;
    private static Map<String,Integer> methodTimesMap = new HashMap<String,Integer>();
    public Log getInstance() {
        if (log == null) {
            log = new Log();
        }
        return log;
    }
    public static void out(String log) {
        if(outDebugLog) {
            if(debugLevel <= LEVEL_ALL) {
                logger.log(SimpleFileLogger.LogLevel.INFO, log);
                System.out.println(log);
            }
        }
    }
    public static void error(String log) {
        logger.log(SimpleFileLogger.LogLevel.ERROR, log);
        System.out.println(log);
    }
    public static void error(String log, Exception e) {
        e.printStackTrace();
        logger.log(SimpleFileLogger.LogLevel.ERROR, log);
        System.out.println(log);
    }
    public static long methodStart(String className, String method) {
        return methodStart(className, method, "");
    }
    public static long methodStart(String className, String method, String message) {
        long time = System.currentTimeMillis();
        String key = className + ":" + method;

        if (debugLevel <= LEVEL_LONGTIME_FUNC) {//添加调用层级
            Integer methodtimes = methodTimesMap.get(key);
            if (methodtimes == null) {
                methodtimes = 1;
            } else {
                methodtimes = methodtimes + 1;
            }
            methodTimesMap.put(key, methodtimes);
           if(outMethodStart) {
//            timeMap.put(key, time);
               String outmessage = key + " start("+methodtimes+")###" + time + ":" + message;
               logger.log(SimpleFileLogger.LogLevel.INFO, outmessage);
               System.out.println(message);
           }
            return time;
        }
        return time;
    }

    public static void methodEnd(String className, String method,String message,long starttime) {
        if(debugLevel <= LEVEL_FUNC) {
            methodEndDebugTime(className, method, message, starttime);
        }
    }

    public static void methodEndDebugTime(String className, String method,String message, long starttime) {
        if(debugLevel <= LEVEL_LONGTIME_FUNC) {
            String key = className + ":" + method;
            //################ 添加调用层级 #################
            Integer methodtimes = methodTimesMap.get(key);


           //################ 添加调用时间 #################
            long endtime = System.currentTimeMillis();
//            long starttime = timeMap.get(key);

            long duration = endtime - starttime;
            String outmessage = key + " end  ("+ methodtimes +")###duration:(" + (endtime - starttime) + ") " + message;

            if(duration>10) {
                 logger.log(SimpleFileLogger.LogLevel.INFO, outmessage);
//                System.out.println();
                System.out.println(outmessage);
            } else if(debugLevel<=LEVEL_FUNC) {
                 logger.log(SimpleFileLogger.LogLevel.INFO, outmessage);
                System.out.println(outmessage);
            }


            //################ 添加调用层级 #################

            methodTimesMap.put(key, methodtimes - 1);
        }
    }


    // 原来的 outKeyVar(IKLineNode …) 随 updater.script 包一起删掉了：更新器只负责抓数、
    // 落库，没有任何脚本求值链路，那个方法从迁进更新器起就没有调用点。

    public static void outSql( String message) {
        if(outSql) {

            String outSql = "sqlL" + message;
            logger.log(SimpleFileLogger.LogLevel.INFO, message);
            System.out.println(message);
        }
    }

    public static void outIndexData(String message) {
        if(outCurIndex) {
            logger.log(SimpleFileLogger.LogLevel.INFO, message);
            System.out.println(message);
        }
    }

    public static void info(String info) {
        if(debugLevel <= LEVEL_INFO) {
            logger.info("info: " + info);
            System.out.println("info: " + info);
        }
    }

    public static String appendHeaderLog(String header) {
        int len= 12;
        StringBuilder logbuf = new StringBuilder();
        appendToLen(logbuf,header,len);
        logbuf.append(":");
        return logbuf.toString();
    }

    public static String appendNameLog(String header) {
        int len= 60;
        StringBuilder logbuf = new StringBuilder();
        appendToLen(logbuf,header,len);
        return logbuf.toString();
    }

    public static String appendResultLog(Number result) {
        int len= 10;
        StringBuilder sb = new StringBuilder();
        appendToLen(sb,String.valueOf(result),len);
        return sb.toString();
    }

    public static String appendToLen(String text, int len) {
        StringBuilder sb  = new StringBuilder();
        appendToLen(sb, text, len);
        return sb.toString();
    }

    private static String appendCode(String text) {
        StringBuilder sb  = new StringBuilder();

        int textLen = getGBKBasedLength(text);
        if(text.length()<6) {
            for(int i= textLen;i<6;i++) {
                sb.append("0");
            }
        }
        sb.append(text);
        return sb.toString();
    }

    private static void appendToLen(StringBuilder logbuf, String text, int len) {
        logbuf.append(text);
        int textLen = getGBKBasedLength(text);
        if(text.length()<len) {
            for(int i= textLen;i<len;i++) {
                logbuf.append(" ");
            }
        }
    }

    /**
     * 基于GBK编码计算长度，汉字占2字节，英文占1字节
     */
    public static int getGBKBasedLength(String str) {
        if (str == null) return 0;
        try {
            byte[] bytes = str.getBytes("GBK");
            return bytes.length;
        } catch (Exception e) {
            return str.length();
        }
    }

}
