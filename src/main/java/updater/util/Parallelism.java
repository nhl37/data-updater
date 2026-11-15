package updater.util;

import updater.cfg.AppConfigManager;

/**
 * 并行度：一台机器上同时处理几只股票。
 *
 * <p>盘后数据下载是「每只股票一个独立 .db 文件」的活，彼此不共享任何东西，
 * 天然可以并行。串行跑的时候，每只都要等一次 SQLite 提交落盘（2~3 次 fsync），
 * 这段等待完全是磁盘在耗时间，CPU 闲着 —— 并行就是把这些等待叠起来。</p>
 *
 * <p><b>路数怎么定</b>（用户定的规矩，别随手改）：
 * <ul>
 *   <li>8 核及以下：核数 - 2，给日常操作留两个核</li>
 *   <li>8 核以上：核数的 70%</li>
 *   <li>下限 2、上限 32：至少能并行，又不至于把文件句柄和磁盘队列打爆</li>
 * </ul>
 * 2 核 → 2，4 核 → 2，6 核 → 4，8 核 → 6，12 核 → 8，16 核 → 11，32 核 → 22。</p>
 *
 * <p>config.properties 里 {@code updater.threads} 填正整数则强制用这个值，
 * 填 0（默认）表示自动。机械盘上并行太高反而慢（磁头来回找道），
 * 体检提示慢盘时把这里调到 2~4。</p>
 */
public final class Parallelism {

    /** 配置里填这个值（或任何非正数）表示「按核数自动算」 */
    public static final int AUTO = 0;
    /** 下限：再少就没有并行的意义了 */
    public static final int MIN = 2;
    /** 上限：再多就会撞文件句柄 / 磁盘队列上限，收益也不涨 */
    public static final int MAX = 32;

    private Parallelism() {
    }

    /** 本机核数（至少 1，理论上不会返回 0） */
    public static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * 按核数算并行度，纯函数，便于验证。
     *
     * @param cores 核数（&lt;1 当作 1）
     */
    public static int forCores(int cores) {
        int n = cores <= 8 ? cores - 2 : (int) Math.floor(cores * 0.7);
        if (n < MIN) {
            n = MIN;
        }
        if (n > MAX) {
            n = MAX;
        }
        return n;
    }

    /** 把配置值折算成实际并行度：非正数 = 自动 */
    public static int resolve(int configured) {
        if (configured > 0) {
            return Math.min(MAX, Math.max(1, configured));
        }
        return forCores(cores());
    }

    /** 读 {@code updater.threads} 折算并行度；读配置失败按自动算 */
    public static int resolve() {
        int configured = AUTO;
        try {
            configured = AppConfigManager.getInstance().getConfigReader()
                    .getInt("updater.threads", AUTO);
        } catch (Throwable ignored) {
            // 配置没读出来就用自动值，绝不能因为读配置把更新任务卡住
        }
        return resolve(configured);
    }

    /** 一句人话，写进更新日志：「16 核 → 11 路并行」 */
    public static String describe(int workers) {
        return cores() + " 核 → " + workers + " 路并行";
    }
}
