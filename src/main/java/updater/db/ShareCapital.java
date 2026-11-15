package updater.db;

/**
 * 一条股本 / 股东数据：某只股票在某个日期（报告期或股本变动日）的股本结构。
 * <p>
 * 为什么单独建这张表：日线库（T_D_Day）里只有开高低收、成交量和成交额，
 * 没有流通股本就算不出换手率。而股本不是每天变的，只在增发、解禁、送转、
 * 可转债转股这些时点跳一次，所以按「日期 + 值」存一条时间线，
 * 取某天的换手率时找「不晚于该天的最近一条」。
 * <p>
 * 字段全部可空（用 {@code null} 表示该来源没有提供这一项）：
 * akshare 的股本变动只给总股本/流通股，十大流通股东只给持股数与占比，
 * 两边各自只填自己有的列，合并时按 (code, reportDate) 缝合。
 */
public final class ShareCapital {

    /** 库内代码，如 sh600000 */
    public String code;
    /** 数据日期 yyyyMMdd：报告期，或股本变动生效日 */
    public String reportDate;

    /** 总股本（股） */
    public Double totalShares;
    /** 已上市流通A股（股）—— 换手率的分母 */
    public Double floatShares;
    /** 未上市流通 / 限售股（股） */
    public Double restrictedShares;

    /** 前十大流通股东持股数量合计（股） */
    public Double top10HolderShares;
    /** 前十大流通股东合计占流通股比例（%） */
    public Double top10HolderRatio;
    /** 第一大流通股东持股数量（股） */
    public Double top1HolderShares;

    /** 股东户数（户） */
    public Double holderCount;

    /** 股本变动原因，如「送股」「增发新股上市」 */
    public String changeReason = "";
    /** 数据来源：FIN（本地财报字段）/ CNINFO（巨潮股本变动）/ EM（东财十大流通股东） */
    public String source = "";
    /** 公告日期 yyyyMMdd */
    public String annDate = "";

    public ShareCapital() {
    }

    public ShareCapital(String code, String reportDate) {
        this.code = code;
        this.reportDate = reportDate;
    }

    /**
     * 自由流通股 = 流通A股 - 前十大流通股东合计持股。
     * 两项缺一就返回 null，交给调用方决定退回哪个口径。
     */
    public Double freeFloatShares() {
        if (floatShares == null || top10HolderShares == null) {
            return null;
        }
        return Math.max(0.0, floatShares - top10HolderShares);
    }

    /**
     * 合并另一条来源的数据：本对象为空的字段用另一条的补上（不反向覆盖）。
     *
     * @return 是否真的补到了新字段
     */
    public boolean mergeFrom(ShareCapital other) {
        if (other == null) {
            return false;
        }
        boolean changed = false;
        if (totalShares == null && other.totalShares != null) {
            totalShares = other.totalShares;
            changed = true;
        }
        if (floatShares == null && other.floatShares != null) {
            floatShares = other.floatShares;
            changed = true;
        }
        if (restrictedShares == null && other.restrictedShares != null) {
            restrictedShares = other.restrictedShares;
            changed = true;
        }
        if (top10HolderShares == null && other.top10HolderShares != null) {
            top10HolderShares = other.top10HolderShares;
            changed = true;
        }
        if (top10HolderRatio == null && other.top10HolderRatio != null) {
            top10HolderRatio = other.top10HolderRatio;
            changed = true;
        }
        if (top1HolderShares == null && other.top1HolderShares != null) {
            top1HolderShares = other.top1HolderShares;
            changed = true;
        }
        if (holderCount == null && other.holderCount != null) {
            holderCount = other.holderCount;
            changed = true;
        }
        if ((changeReason == null || changeReason.isEmpty())
                && other.changeReason != null && !other.changeReason.isEmpty()) {
            changeReason = other.changeReason;
            changed = true;
        }
        if ((annDate == null || annDate.isEmpty())
                && other.annDate != null && !other.annDate.isEmpty()) {
            annDate = other.annDate;
            changed = true;
        }
        if (other.source != null && !other.source.isEmpty()
                && (source == null || source.isEmpty() || !source.contains(other.source))) {
            source = (source == null || source.isEmpty()) ? other.source : source + "+" + other.source;
            changed = true;
        }
        return changed;
    }

    /**
     * 是否有任何股本口径的数值（只有股东数据的行不算）
     */
    public boolean hasShareData() {
        return totalShares != null || floatShares != null;
    }

    @Override
    public String toString() {
        return "ShareCapital{" + code + ' ' + reportDate
                + ", total=" + totalShares + ", float=" + floatShares
                + ", top10=" + top10HolderShares + ", src=" + source + '}';
    }
}
