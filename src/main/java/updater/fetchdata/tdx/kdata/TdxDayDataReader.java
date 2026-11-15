package updater.fetchdata.tdx.kdata;

import updater.util.SecurityType;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地数据来源日线（{@code vipdoc/sh|sz/lday/*.day}）读取。
 *
 * <p>文件是定长记录：每条 <b>32 字节</b>、小端序、按日期升序拼接，
 * 字段依次是 date/open/high/low/close/int、amount(float)、volume、保留位。</p>
 *
 * <p><b>价格倍数按品种走，不是一律 100</b>（见 {@link SecurityType#priceDivisor}）：
 * 个股是「分」——997 表示 9.97 元；场内基金是「厘」——{@code sh510300} 的 4150 表示 4.150 元。
 * 用同一个 100 去读基金，价格会整体放大 10 倍，读出来不报错、只是全错。</p>
 *
 * <p><b>成交量读成 long</b>：深市大 ETF 一天能成交 40 亿份以上，超过 int 上限会变负数；
 * 而且基金的成交量单位在同一年里会「份」和「手」混着出现，统一用
 * {@link SecurityType#normalizeVolume} 按成交额定标归到「份」。</p>
 *
 * <p><b>效率上的两个约定</b>（全市场 5000+ 只、每只每天都要读，别在这里做多余的事）：
 * <ol>
 *   <li>解析走复用的同一个 {@link ByteBuffer}，不是每条 {@code ByteBuffer.wrap} 一个新对象 ——
 *       一只股票历史上千条记录，原来等于每只白扔上千个对象。</li>
 *   <li>只要最近 N 天就别读整个文件：按 {@code (总条数 - N) * 32} 直接 seek 过去。
 *       盘后下载只取最近 5 天，原来却把 5~10 年的记录全读进来再 {@code subList}，
 *       99% 的解析工作都是白做的。</li>
 * </ol></p>
 */
public class TdxDayDataReader {

    /** 本地数据来源日线一条记录的字节数 */
    public static final int RECORD_BYTES = 32;

    /** 解析一条记录（调用方负责把 position 归零） */
    private static TdxStockData readLine(ByteBuffer bb, int priceDivisor) {
        int date = bb.getInt();
        int open = bb.getInt();
        int high = bb.getInt();
        int low = bb.getInt();
        int close = bb.getInt();
        float amount = bb.getFloat();
        // 成交量是 32 位，但基金按「份」记账能超过 int 上限（40 亿+），必须按无符号读
        long volume = Integer.toUnsignedLong(bb.getInt());
        bb.getInt(); // 保留字段，跳过

        double closeYuan = close / (double) priceDivisor;
        TdxStockData stockData = new TdxStockData();
        stockData.setDate(date);
        stockData.setOpen((float) (open / (double) priceDivisor));
        stockData.setHigh((float) (high / (double) priceDivisor));
        stockData.setLow((float) (low / (double) priceDivisor));
        stockData.setClose((float) closeYuan);
        stockData.setAmount(amount);
        stockData.setVolume(SecurityType.normalizeVolume(volume, amount, closeYuan));
        return stockData;
    }

    /** 从第 {@code startRecord} 条开始读 {@code count} 条 */
    private static List<TdxStockData> readRecords(RandomAccessFile raf, long startRecord, int count,
                                                  int priceDivisor) throws IOException {
        List<TdxStockData> list = new ArrayList<>(Math.max(16, Math.min(count, 2048)));
        if (count <= 0) {
            return list;
        }
        raf.seek(startRecord * RECORD_BYTES);
        byte[] buffer = new byte[RECORD_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            raf.readFully(buffer);
            bb.clear();
            list.add(readLine(bb, priceDivisor));
        }
        return list;
    }

    /** 文件对应的价格倍数：按文件名里的代码判断个股 / 基金 */
    private static int divisorOf(String filePath) {
        return SecurityType.priceDivisor(new File(filePath).getName());
    }

    /** 读整个文件的全部日线（补充历史记录用） */
    public static List<TdxStockData> readAllDay(String filePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            long records = raf.length() / RECORD_BYTES;
            if (records <= 0) {
                return new ArrayList<>();
            }
            return readRecords(raf, 0L, (int) records, divisorOf(filePath));
        }
    }

    /**
     * 只读最近 {@code days} 条日线（盘后下载用）。
     *
     * <p>文件尾部若有不完整的残余字节（非 32 的整数倍），按整条向下取整忽略。</p>
     */
    public static List<TdxStockData> readLastDays(String filePath, int days) throws IOException {
        if (days <= 0) {
            return new ArrayList<>();
        }
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            long records = raf.length() / RECORD_BYTES;
            if (records <= 0) {
                return new ArrayList<>();
            }
            int count = (int) Math.min(days, records);
            return readRecords(raf, records - count, count, divisorOf(filePath));
        }
    }

    /**
     * 读文件的<b>第一条</b>记录。
     *
     * <p>注意这是最早的一天，不是最新的一天 —— 「按天更新当日」那条老链路用的是它，
     * 语义保持原样没有改动。</p>
     */
    public static TdxStockData readOneDay(String filePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            if (raf.length() < RECORD_BYTES) {
                return null;
            }
            return readRecords(raf, 0L, 1, divisorOf(filePath)).get(0);
        }
    }
}
