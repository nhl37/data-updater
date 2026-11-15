package updater.fetchdata.min;

import java.io.IOException;

/**
 * 线路明确在<b>拒绝这台机器</b>（HTTP 403 / 429），而不是「偶尔断一下」。
 *
 * <p>单独一个类型是为了让上层分得清两种失败：</p>
 * <ul>
 *   <li>普通断开 / 超时 → 换个数据源就好，IP 没问题，重试也有意义；</li>
 *   <li>被拒绝 → 换数据源可能也没用（三家都不给），这时候才值得动 IP。</li>
 * </ul>
 *
 * <p>顺带一个好处：403 不再走 4 次退避重试。被拒绝是立刻就能确定的结论，
 * 再等 3 秒纯属浪费 —— 全市场五千只各等 3 秒就是四个小时。</p>
 */
final class BlockedException extends IOException {

    private static final long serialVersionUID = 1L;

    BlockedException(String message) {
        super(message);
    }
}
