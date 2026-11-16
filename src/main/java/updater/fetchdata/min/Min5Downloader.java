package updater.fetchdata.min;

import updater.fetchdata.tdx.min.MinDataUpdater;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 5 分钟线的取数入口：同一个结果（写进 {@code T_D_Min5}），几条线路任选一条。
 *
 * <p>线路次序固定：<b>先本地数据来源的分钟文件</b>（一次读全市场，最快、窗口最长），
 * 读不出来再走<b>在线逐只取</b>（只作兜底，慢、窗口短，但不需要用户先准备本地文件）。</p>
 *
 * <p>在线这一档本身又分几家：{@link #ONLINE} 让三家依次兜底（东财 → 新浪 → 腾讯），
 * 也可以点名只要某一家（{@link #EASTMONEY} / {@link #SINA} / {@link #TENCENT}），
 * 用于用户明确指定、或者某一家临时抽风时换过去。所有线路写出来的口径完全一致，
 * 上层拿到的返回值也一样，所以调用方不必关心这一次到底走了哪条。</p>
 *
 * <p><b>失败要抛出</b>：线路不可用、一只都没补到，都必须往上抛，不能静静地返回 0 ——
 * 上层正是靠这个异常决定「换下一条线路再试」。</p>
 */
public final class Min5Downloader {

    /** 本地数据来源里的分钟线文件 */
    public static final String LOCAL = "tdx";
    /** 在线逐只取，多家依次兜底 */
    public static final String ONLINE = "online";
    /** 在线逐只取，只走东方财富 */
    public static final String EASTMONEY = EastMoneyMin5.ID;
    /** 在线逐只取，只走新浪 */
    public static final String SINA = SinaMin5.ID;
    /** 在线逐只取，只走腾讯 */
    public static final String TENCENT = TencentMin5.ID;

    private Min5Downloader() {
    }

    /** 这条线路是否需要先配好本地数据来源目录 */
    public static boolean needsLocalFiles(String sourceId) {
        return LOCAL.equals(normalize(sourceId));
    }

    /**
     * 按线路补 5 分钟线。
     *
     * @param sourceId  线路；空串 / 不认识的值折成默认线路（本地文件）
     * @param log       进度输出，null 只写日志
     * @param cancel    停止标志，可为 null
     * @param onlyCodes 只补这几只，null = 全部
     * @return {成功只数, 写入根数, 跳过只数}
     * @throws Exception 这一条线路不可用（调用方应当换下一条）
     */
    public static int[] run(String sourceId, Consumer<String> log, AtomicBoolean cancel,
                            Collection<String> onlyCodes) throws Exception {
        String id = normalize(sourceId);
        if (ONLINE.equals(id)) {
            return Min5Online.run(log, cancel, onlyCodes);
        }
        Min5BarSource single = Min5Online.sourceOf(id);
        if (single != null) {
            List<Min5BarSource> only = new java.util.ArrayList<Min5BarSource>(1);
            only.add(single);
            return Min5Online.run(only, log, cancel, onlyCodes);
        }
        int[] stat = MinDataUpdater.update(log, cancel, onlyCodes);
        if (stat[0] == 0) {
            // 文件目录在、但一只都没读到：对上层来说跟「线路不通」是一回事，该换线路
            throw new IOException("本地数据来源里没有可用的分钟线文件（一只都没读到）");
        }
        return stat;
    }

    /**
     * 只用本地分钟文件，把库补到「本地文件最深那一刻」—— 连中间断掉的几段一起补。
     *
     * <p>这是「分钟数据」画面上那个「补全到本地最新」按钮走的入口：它<b>不联网</b>，
     * 因为要补的就是「本地那份数据到哪，库里就得到哪」这件事。日常盘后那条只补末根之后的
     * 增量，中间断过的几天它看不见。</p>
     *
     * @param onlyCodes 只补这几只，null = 全部
     * @return {成功只数, 写入根数, 跳过只数}
     */
    public static int[] runLocalFill(Consumer<String> log, AtomicBoolean cancel,
                                     Collection<String> onlyCodes) throws Exception {
        int[] stat = MinDataUpdater.update(log, cancel, onlyCodes, true);
        if (stat[0] == 0) {
            throw new IOException("本地数据来源里没有可用的分钟线文件（一只都没读到）");
        }
        return stat;
    }

    private static String normalize(String sourceId) {
        String id = sourceId == null ? "" : sourceId.trim().toLowerCase(Locale.ROOT);
        if (ONLINE.equals(id)) {
            return ONLINE;
        }
        if (Min5Online.sourceOf(id) != null) {
            return id;
        }
        return LOCAL;
    }
}
