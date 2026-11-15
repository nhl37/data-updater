package updater.fetchdata.tdx.kdata;
// 数据封装类
public  class TdxStockData {
    private int date;
    private float open;
    private float high;
    private float low;
    private float close;
    private float amount;
    /**
     * 成交量（股 / 份）。
     *
     * <p><b>必须是 long</b>：深市大 ETF 一天的成交量（份）能到 40 亿以上，
     * 超过 {@code int} 上限（约 21.47 亿）会读成负数，写进库里 VOL 变负，
     * 量比 / 均量类因子全部失真且不报错。</p>
     */
    private long volume;

    // 省略getter和setter方法
    public int getDate() { return date; }
    public void setDate(int date) { this.date = date; }

    public float getOpen() { return open; }
    public void setOpen(float open) { this.open = open; }

    public float getHigh() { return high; }
    public void setHigh(float high) { this.high = high; }

    public float getLow() { return low; }
    public void setLow(float low) { this.low = low; }

    public float getClose() { return close; }
    public void setClose(float close) { this.close = close; }

    public float getAmount() { return amount; }
    public void setAmount(float amount) { this.amount = amount; }

    public long getVolume() { return volume; }
    public void setVolume(long volume) { this.volume = volume; }

    @Override
    public String toString() {
        return String.format("Date: %d, O: %.3f, H: %.3f, L: %.3f, C: %.3f, Amount: %.2f, Volume: %d",
                date, open, high, low, close, amount, volume);
    }
}
