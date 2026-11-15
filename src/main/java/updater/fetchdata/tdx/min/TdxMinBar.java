package updater.fetchdata.tdx.min;

/**
 * 本地数据来源分钟线的一根 K（5 分钟或 1 分钟，取决于从哪个文件读出来的）。
 *
 * <p>价格统一换算成「分」（int），与 {@code T_D_Day} 里存的口径一致——日线文件里本来就是分，
 * 分钟线文件里是 float 元，直接乘 100 取整。这样 30 分钟与日线比较时不会差出一个小数位。</p>
 *
 * <p>时间用 {@code yyyyMMddHHmm} 的 long：202610301500。日线是 {@code yyyyMMdd} 的 int，
 * 取 {@code time / 10000} 就回到那一天的日期，跨天比较和分组都靠这个。</p>
 */
public class TdxMinBar {

    /** yyyyMMddHHmm */
    private long time;
    private int open;
    private int high;
    private int low;
    private int close;
    private long vol;
    private long amo;

    public TdxMinBar() {
    }

    public TdxMinBar(long time, int open, int high, int low, int close, long vol, long amo) {
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.vol = vol;
        this.amo = amo;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    /** 这一天（yyyyMMdd），日线口径 */
    public int getDay() {
        return (int) (time / 10000);
    }

    public int getOpen() {
        return open;
    }

    public void setOpen(int open) {
        this.open = open;
    }

    public int getHigh() {
        return high;
    }

    public void setHigh(int high) {
        this.high = high;
    }

    public int getLow() {
        return low;
    }

    public void setLow(int low) {
        this.low = low;
    }

    public int getClose() {
        return close;
    }

    public void setClose(int close) {
        this.close = close;
    }

    public long getVol() {
        return vol;
    }

    public void setVol(long vol) {
        this.vol = vol;
    }

    public long getAmo() {
        return amo;
    }

    public void setAmo(long amo) {
        this.amo = amo;
    }

    @Override
    public String toString() {
        return time + " O:" + open + " H:" + high + " L:" + low + " C:" + close
                + " V:" + vol + " A:" + amo;
    }
}
