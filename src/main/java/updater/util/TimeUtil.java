package updater.util;

import java.text.ParseException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Calendar;
import java.util.Date;
import java.text.SimpleDateFormat;

public class TimeUtil {

    public static LocalDate firstDayOfWeek(long time) {

        // 2. 解析为 LocalDate
        LocalDate date = LocalDate.parse(String.valueOf(time), DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 3. 计算当周的第一天（周日）：
        //    - 如果当前是周日，直接返回当天
        //    - 否则，向前调整到上一个周日
        LocalDate firstDayOfWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return firstDayOfWeek;
    }
    public static long time(String timestr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime dateTime = LocalDateTime.parse(timestr, formatter);

        return dateTime.toEpochSecond(ZoneOffset.UTC);
    }

    public static String formatDate(String originalDate, int addDays) throws ParseException {

        // 定义输入和输出格式
        SimpleDateFormat inputFormat = new SimpleDateFormat("dd/MM/yyyy");
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMdd");

        // 解析日期
        Date date = inputFormat.parse(originalDate);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.DATE, addDays);
        date =  calendar.getTime();
        // 格式化为新格式
        String formattedDate = outputFormat.format(date);

        return formattedDate;

    }

    public static String formatDate(String originalDate, String originalFormat) throws ParseException {

            // 定义输入和输出格式
            SimpleDateFormat inputFormat = new SimpleDateFormat(originalFormat);
            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMdd");

            // 解析日期
            Date date = inputFormat.parse(originalDate);

            // 格式化为新格式
            String formattedDate = outputFormat.format(date);

            return formattedDate;

    }
    public static String getDateStr(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        return sdf.format(date);
    }

    public static int yyyyMMdd(long time) {

        Date date = new Date(time);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");

        return Integer.parseInt(sdf.format(date));
    }

    public static int getWorkYyyyMMdd() {
        Calendar calendar = Calendar.getInstance();
        int weekday = calendar.get(Calendar.DAY_OF_WEEK);
        Date date = new Date();
        calendar.setTime(date);
        if(weekday == Calendar.SATURDAY ) {
            calendar.add(Calendar.DAY_OF_WEEK, -1);
            date =  calendar.getTime();
        } else if(weekday == Calendar.SUNDAY){
            calendar.add(Calendar.DAY_OF_WEEK, -2);
            date =  calendar.getTime();
        }
        String dateStr = TimeUtil.getDateStr(date);
        return Integer.parseInt(dateStr);
    }

    public static boolean isValid(Date date) {
       Calendar calendar = Calendar.getInstance();
       int curYear = calendar.get(Calendar.YEAR);
       calendar.setTime(date);
       int pYear = calendar.get(Calendar.YEAR);
       if(pYear<1990|| pYear>curYear) {
           return false;
       } else {
           return true;
       }
    }


    public static String getCurrentDate() {
        return getDateStr(new Date());
    }
    public static String getCurrentDate(String format) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return sdf.format(new Date());
    }

    // 辅助方法：判断两个时间是否为同一周
    public static  boolean isSameWeek(int time1, int time2) {
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();

        // 将 long 时间转换为 Calendar 对象
        // time 格式为 yyyyMMdd，需要解析
        int year1 = (time1 / 10000);
        int month1 = ((time1 % 10000) / 100);
        int day1 = (time1 % 100);

        int year2 = (time2 / 10000);
        int month2 = ((time2 % 10000) / 100);
        int day2 = (time2 % 100);

        cal1.set(year1, month1 - 1, day1);
        cal2.set(year2, month2 - 1, day2);

        // 设置每周的第一天为周一（中国习惯）
        cal1.setFirstDayOfWeek(Calendar.MONDAY);
        cal2.setFirstDayOfWeek(Calendar.MONDAY);

        // 比较年份和周数
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.WEEK_OF_YEAR) == cal2.get(Calendar.WEEK_OF_YEAR);
    }


    // 辅助方法：判断两个时间是否为同一个月
    public static  boolean isSameMonth(long time1, long time2) {
        // 直接比较年份和月份
        int yearMonth1 = (int)(time1 / 100);  // 获取年月部分
        int yearMonth2 = (int)(time2 / 100);  // 获取年月部分

        return yearMonth1 == yearMonth2;
    }
}