package updater.util;

/**
 * 一个「正在干活的执行者」的阶段状态槽。
 *
 * <p>并行下载时每个 worker 线程一个，看门狗线程直接读这些 volatile 字段，
 * 判断谁卡住了、卡在哪一步。<b>业务线程只做 volatile 赋值</b>：
 * 不加锁、不拼字符串、不做 IO、不格式化时间 —— 一条股票会打 6 次点，
 * 全市场就是三万次，任何一点重量都会被放大成秒级开销。
 *
 * <p>阶段名、耗时这些「人话」全部由看门狗线程或界面线程在需要时现拼，
 * 业务线程永远只写一个 int。</p>
 */
public final class StageSink {

    /** 空闲（两只股票之间的空隙，或 worker 已退出） */
    public static final int IDLE = 0;
    /** 扫描 / 列目录 */
    public static final int SCAN = 1;
    /** 读本地数据来源 .day 文件 */
    public static final int READ = 2;
    /** 打开 / 新建 SQLite 库 */
    public static final int OPEN = 3;
    /** 建表 / 探测表结构 */
    public static final int DDL = 4;
    /** 写库（INSERT） */
    public static final int WRITE = 5;
    /** 提交事务（等 fsync，最慢的一步） */
    public static final int COMMIT = 6;
    /** 关连接 */
    public static final int CLOSE = 7;
    /** 自由文本阶段（补历史那种一句话进度） */
    public static final int OTHER = 8;

    private final String name;

    /** 当前在处理哪一只（股票代码或文件名） */
    private volatile String code = "";
    /** 当前阶段，{@link #IDLE}~{@link #OTHER} */
    private volatile int stage = IDLE;
    /** stage == OTHER 时的自由文本 */
    private volatile String custom = "";
    /** 进入当前阶段的时刻（毫秒），看门狗用它算「停了多久」 */
    private volatile long stageSince = System.currentTimeMillis();
    /** 这只股票开始处理的时刻（毫秒），用于算单只耗时 */
    private volatile long startedAt = stageSince;
    /** 被判僵死、已经放弃（线程可能还挂在 native 里，但不算活跃） */
    private volatile boolean abandoned;
    /** 本次停留已经告警过几次 */
    volatile int warned;
    /** 上一次告警的时刻 */
    volatile long lastWarnMillis;

    StageSink(String name) {
        this.name = name == null ? "" : name;
    }

    public String name() {
        return name;
    }

    /** 开始处理一只新的：重置计时与告警计数 */
    public void begin(String code) {
        this.code = code == null ? "" : code;
        this.stage = IDLE;
        this.custom = "";
        long now = System.currentTimeMillis();
        this.stageSince = now;
        this.startedAt = now;
        this.abandoned = false;
        this.warned = 0;
        this.lastWarnMillis = 0L;
    }

    /**
     * 换阶段：两次 volatile 写，纳秒级。
     *
     * <p>顺手把告警计数清零 —— 有新进展就说明没卡住，
     * 下一个阶段再停住应该重新算一次。</p>
     */
    public void stage(int stageId) {
        this.stage = stageId;
        this.stageSince = System.currentTimeMillis();
        this.warned = 0;
    }

    /** 自由文本阶段（一句话进度，补历史那种场景用） */
    public void stage(String text) {
        this.custom = text == null ? "" : text;
        this.stage = OTHER;
        this.stageSince = System.currentTimeMillis();
        this.warned = 0;
    }

    /** 这一只处理完了（成功或失败都调），回到空闲 */
    public void finish() {
        this.stage = IDLE;
        this.code = "";
        this.stageSince = System.currentTimeMillis();
    }

    /** 判为僵死并放弃：之后看门狗不再管它，主循环也不再指望它 */
    public void abandon() {
        this.abandoned = true;
        this.stageSince = System.currentTimeMillis();
    }

    public boolean abandoned() {
        return abandoned;
    }

    public String code() {
        return code;
    }

    public int stageId() {
        return stage;
    }

    /** 当前阶段停了多久（毫秒） */
    public long idleMillis() {
        return System.currentTimeMillis() - stageSince;
    }

    /** 这一只开始处理到现在多久（毫秒） */
    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    /** 阶段名（人话） */
    public String stageText() {
        switch (stage) {
            case SCAN:
                return "扫描目录";
            case READ:
                return "读文件";
            case OPEN:
                return "开库";
            case DDL:
                return "建表";
            case WRITE:
                return "写库";
            case COMMIT:
                return "提交";
            case CLOSE:
                return "关库";
            case OTHER:
                return custom.isEmpty() ? "处理中" : custom;
            default:
                return abandoned ? "已放弃" : "空闲";
        }
    }

    /** 一句人话：sh600115（写库） */
    public String describe() {
        String who = code.isEmpty() ? name : code;
        return who + "（" + stageText() + "）";
    }
}
