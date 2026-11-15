package updater.util;

import java.util.List;

/**
 * 逐项进度回调：引擎每处理完一只股票 / 一个季度，就往这里报一次。
 *
 * <p><b>为什么要有它</b>：更新任务真正的粒度是「一只股票一个库」，而原来引擎只往界面
 * 推一句心跳（「已处理 3200/5380」）。用户中断以后无从知道到底是哪几只没下、
 * 哪几只失败、失败原因是什么。有了这个接口，界面就能给每一只留一行台账，
 * 中断重开后还能接着跑。</p>
 *
 * <p>约定：实现方必须是<b>轻量、无阻塞</b>的（worker 线程会直接调用它），
 * 需要落盘 / 刷界面的都自己攒批处理。</p>
 */
public interface ItemProgress {

    /** 空实现：不需要逐项进度时传它，引擎里不用到处判空 */
    ItemProgress NONE = new ItemProgress() {
    };

    /**
     * 本次要做多少项（以及都是哪些），任务开跑前调一次。
     *
     * <p>给了清单，界面就能先铺出「未开始」的行，中断后也知道还剩哪些没做。</p>
     */
    default void planned(List<String> keys) {
    }

    /** 开始处理某一项 */
    default void begin(String key) {
    }

    /** 某一项处理成功，detail 是给用户看的一句结果（如「补 5 根」「新建库」） */
    default void done(String key, String detail) {
    }

    /** 某一项按规则跳过（如本地数据来源里没有这只的数据、本地没有库），不是错误 */
    default void skip(String key, String detail) {
    }

    /** 某一项失败，reason 是给用户看的原因 */
    default void fail(String key, String reason) {
    }
}
