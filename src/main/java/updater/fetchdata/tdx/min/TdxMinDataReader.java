package updater.fetchdata.tdx.min;

import updater.log.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 读本地数据来源的分钟线文件（{@code .lc5} / {@code .lc1}）。
 *
 * <p>格式很简单也很固定：文件头 32 字节，之后每 32 字节一根 K：</p>
 * <pre>
 *   0-1   ushort  日期：year = date/2048 + 2004, month = (date%2048)/100, day = (date%2048)%100
 *   2-3   ushort  时间：hour = t/60, minute = t%60     ← 是这根K的**结束时刻**
 *   4-7   float   开盘价（元）
 *   8-11  float   最高价
 *   12-15 float   最低价
 *   16-19 float   收盘价
 *   20-23 float   成交额（元）
 *   24-27 int     成交量（股 / 份，无符号；深市大 ETF 会超过 int 上限）
 *   28-31 int     保留
 * </pre>
 *
 * <p>个股与场内基金（ETF / LOF）的分钟线是同一套格式，价格直接是「元」、不用按品种换倍数；
 * 只有成交量像日线一样可能混着「手」，用 {@code SecurityType.normalizeVolume} 统一归到「股 / 份」。</p>
 *
 * <p>为什么时间戳是结束时刻：本地数据来源 5 分钟线的第一根标 09:35（覆盖 09:31~09:35），
 * 一天 48 根到 15:00。30 分钟线同理标 10:00 / 10:30 / 11:00 / 11:30 / 13:30 / 14:00 / 14:30 / 15:00。
 * 后面聚合的时候必须沿用这套规则，否则 5 分钟和 30 分钟的时间对不上。</p>
 */
public final class TdxMinDataReader {

    private static final int HEADER_SIZE = 32;
    private static final int RECORD_SIZE = 32;

    private TdxMinDataReader() {
    }

    /**
     * 读一个 .lc5 / .lc1 文件，按时间升序返回。文件不存在或长度不足时返回空表，不抛异常。
     */
    public static List<TdxMinBar> read(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return new ArrayList<>();
        }
        byte[] all = Files.readAllBytes(file.toPath());
        if (all.length < HEADER_SIZE + RECORD_SIZE) {
            return new ArrayList<>();
        }
        int records = (all.length - HEADER_SIZE) / RECORD_SIZE;
        List<TdxMinBar> list = new ArrayList<>(records);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(HEADER_SIZE);

        for (int i = 0; i < records; i++) {
            int date = bb.getShort() & 0xFFFF;
            int minuteOfDay = bb.getShort() & 0xFFFF;
            float open = bb.getFloat();
            float high = bb.getFloat();
            float low = bb.getFloat();
            float close = bb.getFloat();
            float amount = bb.getFloat();
            // 成交量按无符号读：深市大 ETF 的日内累计量能超过 int 上限，读成 int 会变负数
            long volume = Integer.toUnsignedLong(bb.getInt());
            bb.getInt(); // 保留字段

            int year = date / 2048 + 2004;
            int month = (date % 2048) / 100;
            int day = (date % 2048) % 100;
            int hour = minuteOfDay / 60;
            int minute = minuteOfDay % 60;
            // 尾部可能有脏数据（截断写一半的文件），日期越界就丢掉，不要让它污染排序
            if (month < 1 || month > 12 || day < 1 || day > 31
                    || hour > 23 || minute > 59 || open <= 0 || close <= 0) {
                continue;
            }
            long time = year * 100000000L + month * 1000000L + day * 10000L
                    + hour * 100L + minute;
            list.add(new TdxMinBar(time,
                    toFen(open), toFen(high), toFen(low), toFen(close),
                    updater.util.SecurityType.normalizeVolume(volume, amount, close),
                    (long) amount));
        }
        list.sort(Comparator.comparingLong(TdxMinBar::getTime));
        return list;
    }

    /**
     * 把 1 分钟线聚合成 5 分钟线（本机只有 { .lc1 } 时用得上）。
     *
     * <p>分组按「结束时刻向上取整到 5 分钟」：09:31~09:35 → 09:35，09:36~09:40 → 09:40，
     * 与本地数据来源自己的 5 分钟线时间戳完全一致。开=组内第一根开、高/低=极值、收=最后一根收、量额=累计。</p>
     */
    public static List<TdxMinBar> toFiveMinute(List<TdxMinBar> oneMinute) {
        if (oneMinute == null || oneMinute.isEmpty()) {
            return new ArrayList<>();
        }
        List<TdxMinBar> result = new ArrayList<>(oneMinute.size() / 5 + 1);
        long groupTime = Long.MIN_VALUE;
        int open = 0;
        int high = 0;
        int low = 0;
        int close = 0;
        long vol = 0;
        long amo = 0;
        for (TdxMinBar bar : oneMinute) {
            long end = ceilToFive(bar.getTime());
            if (end != groupTime) {
                if (groupTime != Long.MIN_VALUE) {
                    result.add(new TdxMinBar(groupTime, open, high, low, close, vol, amo));
                }
                groupTime = end;
                open = bar.getOpen();
                high = bar.getHigh();
                low = bar.getLow();
                vol = 0;
                amo = 0;
            } else {
                high = Math.max(high, bar.getHigh());
                low = Math.min(low, bar.getLow());
            }
            close = bar.getClose();
            vol += bar.getVol();
            amo += bar.getAmo();
        }
        if (groupTime != Long.MIN_VALUE) {
            result.add(new TdxMinBar(groupTime, open, high, low, close, vol, amo));
        }
        return result;
    }

    /** yyyyMMddHHmm 里的 hh:mm 向上取整到 5 分钟 */
    private static long ceilToFive(long time) {
        int hhmm = (int) (time % 10000);
        int mins = (hhmm / 100) * 60 + hhmm % 100;
        int end = ((mins + 4) / 5) * 5;
        return (time / 10000) * 10000 + (end / 60) * 100 + end % 60;
    }

    /** 元 → 分。用 round 而不是强转，避免 11.67 → 1166 这种少一分的误差 */
    private static int toFen(float yuan) {
        return Math.round(yuan * 100f);
    }

    /**
     * 从文件名取股票代码：{@code sh600000.lc5} → {@code sh600000}。
     * 认不出来的（比如 index 之外的怪文件）返回 null，由调用方跳过。
     */
    public static String codeOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        base = base.trim().toLowerCase();
        if (base.length() < 8) {
            return null;
        }
        String prefix = base.substring(0, 2);
        if (!"sh".equals(prefix) && !"sz".equals(prefix) && !"bj".equals(prefix)) {
            return null;
        }
        for (int i = 2; i < base.length(); i++) {
            if (!Character.isDigit(base.charAt(i))) {
                return null;
            }
        }
        return base;
    }

    /** 读一只股票：优先 .lc5，没有就退回 .lc1 并聚合成 5 分钟 */
    public static List<TdxMinBar> readFiveMinute(File lc5, File lc1) throws IOException {
        if (lc5 != null && lc5.isFile()) {
            List<TdxMinBar> bars = read(lc5);
            if (!bars.isEmpty()) {
                return bars;
            }
        }
        if (lc1 != null && lc1.isFile()) {
            if (Log.outDebugLog) {
                Log.info("只有1分钟线，聚合成5分钟：" + lc1.getName());
            }
            return toFiveMinute(read(lc1));
        }
        return new ArrayList<>();
    }

    /**
     * 「这只股票的 5 分钟线在本地数据来源里到哪一刻为止」：{@code yyyyMMddHHmm}，读不到返回 0。
     *
     * <p>只读文件尾部的几根记录，不解析整个文件 —— 查缺补漏要连查五千多只，
     * 整文件读一遍（每只 200KB 上下）纯属浪费；它只关心「本地有没有落后」。
     * 与 {@link #readFiveMinute} 口径一致：只有 .lc1 时按 5 分钟取整时刻算。</p>
     */
    public static long lastFiveMinuteTime(File lc5, File lc1) {
        long last = lastRecordTime(lc5);
        if (last > 0) {
            return last;
        }
        long oneMinute = lastRecordTime(lc1);
        // 1 分钟要聚合成 5 分钟：末根的结束时刻向上取整到 5 分钟，才和 .lc5 的时间戳对齐
        return oneMinute > 0 ? ceilToFive(oneMinute) : 0;
    }

    /**
     * 读文件里最后一根有效记录的时间；尾部有截断写的脏数据时往前找（最多看 8 根）。
     */
    private static long lastRecordTime(File file) {
        if (file == null || !file.isFile()) {
            return 0;
        }
        int records = (int) ((file.length() - HEADER_SIZE) / RECORD_SIZE);
        if (records <= 0) {
            return 0;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            int look = Math.min(records, 8);
            for (int i = 1; i <= look; i++) {
                long offset = HEADER_SIZE + (long) (records - i) * RECORD_SIZE;
                raf.seek(offset);
                byte[] buf = new byte[RECORD_SIZE];
                raf.readFully(buf);
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                int date = bb.getShort() & 0xFFFF;
                int minuteOfDay = bb.getShort() & 0xFFFF;
                float open = bb.getFloat();
                bb.getFloat();
                bb.getFloat();
                float close = bb.getFloat();
                int year = date / 2048 + 2004;
                int month = (date % 2048) / 100;
                int day = (date % 2048) % 100;
                int hour = minuteOfDay / 60;
                int minute = minuteOfDay % 60;
                if (month < 1 || month > 12 || day < 1 || day > 31
                        || hour > 23 || minute > 59 || open <= 0 || close <= 0) {
                    continue;
                }
                return year * 100000000L + month * 1000000L + day * 10000L
                        + hour * 100L + minute;
            }
        } catch (IOException e) {
            // 文件被占用 / 网络盘掉线：当作「没有分钟源」，不判缺
            return 0;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // 只读文件，关不掉不影响结论
                }
            }
        }
        return 0;
    }
}
