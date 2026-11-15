package updater.fetchdata;

import updater.db.IDivdendWtriteData;

public class DividendData implements IDivdendWtriteData {
    public String stockCode;    // 股票代码
    public int exDividendDate;  // 除权除息日

    public float cashDividend;  // 现金分红 (10派X元)

    public float bonusShares;   // 拆/缩股 比例 (保留原字段用于拆/缩股)

    public float stockGiftCount; // 送转增合计股数 (per 10股)，例如送2转增2则=4

    public float allotment;      // 配股比例 pgbl (每股配股数)
    public float allotmentPrice; // 配股价格 pgjg (元)

    public String text;//除权内容
    public float reverseSplitRatio; //缩股比例

    // 计算总股本变动比例
    public float getTotalShareChange() {
        if(stockGiftCount != 0) {
            return stockGiftCount;
        }
        return bonusShares;
    }

    // 计算每股现金分红
    public float getCashPerShare() {
        return cashDividend ;/// 10.0f;
    }

    @Override
    public String toString() {
        return String.format("股票代码: %s, 除权日期: %s, 分红: %.2f, 拆/增/送股: %.2f, 送转增合计: %.2f, 配股: %.2f, 配股价: %.2f",
                stockCode, exDividendDate, cashDividend, bonusShares, stockGiftCount, allotment, allotmentPrice);
    }

    @Override
    public int getDividendDate() {
        return this.exDividendDate;
    }

    @Override
    public float getFactor(float price) {
        float factor = 1.0f;

        // 送转增因子 (stockGiftCount = 送转增合计股数 per 10股)
        // 例如送2转增2: stockGiftCount=4, 10股变14股, factor = 10/14 ≈ 0.714
        if(stockGiftCount != 0) {
            factor *= 10.0f / (10.0f + stockGiftCount);
        } else if(bonusShares != 0) {
            // 拆/缩股处理 (保留原有逻辑)
            factor *= 1.0f / bonusShares;
        }

        // 现金分红因子
        if(cashDividend != 0) {
            factor *= (price - cashDividend) / price;
        }

        // 配股因子
        if(allotment != 0) {
            float newPrice = (price * 10 + allotmentPrice * allotment) / (10 + allotment);
            factor *= newPrice / price;
        }

        return factor;
    }

    @Override
    public String getText() {
        return this.text;
    }
}
