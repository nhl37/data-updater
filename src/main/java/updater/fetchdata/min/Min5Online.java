package updater.fetchdata.min;

import updater.db.DBConnection;
import updater.fetchdata.tdx.min.MinDataUpdater;
import updater.fetchdata.tdx.min.TdxMinBar;
import updater.log.Log;
import updater.mount.MountConfig;
import updater.net.Redial;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 5 分钟线的<b>在线线路</b>：本地数据来源没有分钟文件（或文件读不出来）时，逐只把 5 分钟 K 线
 * 取回来，写进同一张 {@code T_D_Min5}。
 *
 * <p><b>它是兜底，不是主力</b>：本地文件一次能读全市场，这里每只股票要发一次请求，
 * 全市场跑一轮要几分钟，而且能拿到的历史窗口远小于本地文件。所以默认线路永远是本地文件。</p>
 *
 * <p><b>在线这一档由多家组成，逐只换源</b>：默认顺序是
 * {@link EastMoneyMin5 东方财富} → {@link SinaMin5 新浪} → {@link TencentMin5 腾讯}。
 * 一只股票在第一家取不到（被限流、断连、这只没数据）就换下一家，所以
 * 「东财挂了整趟白跑」那种事不会再有 —— 也不会因为一家慢就整批卡住。
 * 也可以只指定一家跑（用户在某条线路上手选了它）。</p>
 *
 * <p><b>口径与本地文件一侧完全一致</b>（{@link TdxMinBar}、{@link Min5Bars} 与
 * {@link MinDataUpdater} 是唯一写入口）：价格存「分」、不复权；{@code TIME} 是这一根的
 * <b>结束</b>时刻；{@code VOL} 是「股」、{@code AMO} 是「元」，都是每根的量额；
 * 只补不删，写入统一走 {@link MinDataUpdater#writeBars(String, List, boolean)}。</p>
 *
 * <p>盘中跑的时候最后一根还没走完，接口给的是「到现在为止」的量 —— 这跟本地文件的行为一样，
 * 下次再跑会用同一时刻的新值覆盖，所以不会留下半根的脏数据。</p>
 *
 * <p><b>三家一条都取不到时，先试一次换 IP</b>：这是「本机被这个站限流」最直接的表现。
 * 用户在路径设置里填过重拨命令（{@link Redial}）就跑它一次、等网络回来再跑一轮；
 * 没填就一切照旧。取到一部分不动 IP —— 那说明线路是通的，为几十只股票断网半分钟不值得。</p>
 */
public final class Min5Online {

    /**
     * 并发数。以前是 6 —— 对方会掐，表现就是面板上零星几条「连接被提前关闭」；
     * 压到 3 只并发，整体耗时只多几十秒，但偶发断连基本消失。别再往上调。
     */
    private static final int THREADS = 3;

    /**
     * 连续失败到这个数才认定「这条线路不通」。
     * 偶发的断连不该让整条线路被判死 —— 新机器上这条路是唯一的路，误判就是整趟白跑。
     */
    private static final int GIVE_UP_AFTER = 20;

    /**
     * 某一家连续几只取不到就先把它摘掉，剩下的交给别家。
     *
     * <p>这条不是锦上添花：一家挂了（被限流、接口改了、本机连不上它），每只股票仍要
     * 白跑 4 次重试，全市场五千只就是几小时。连续 3 只失败基本只能是那家自己的问题 ——
     * 单只股票出问题不会连着三只。</p>
     */
    private static final int SOURCE_GIVE_UP_AFTER = 3;

    /** 批次超时：全市场走不完就是线路废了，不能无限等 */
    private static final long PASS_TIMEOUT_MINUTES = 60;

    private Min5Online() {
    }

    /** 在线这一档的默认顺序：三家依次兜底 */
    public static List<Min5BarSource> allSources() {
        List<Min5BarSource> list = new ArrayList<Min5BarSource>(3);
        list.add(EastMoneyMin5.INSTANCE);
        list.add(SinaMin5.INSTANCE);
        list.add(TencentMin5.INSTANCE);
        return list;
    }

    /** 线路 id → 单家线路；不认识返回 null（由调用方折成默认线路） */
    public static Min5BarSource sourceOf(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (EastMoneyMin5.ID.equals(key)) {
            return EastMoneyMin5.INSTANCE;
        }
        if (SinaMin5.ID.equals(key)) {
            return SinaMin5.INSTANCE;
        }
        if (TencentMin5.ID.equals(key)) {
            return TencentMin5.INSTANCE;
        }
        return null;
    }

    /** 三家依次兜底 */
    public static int[] run(Consumer<String> log, AtomicBoolean cancel,
                            Collection<String> onlyCodes) throws Exception {
        return run(allSources(), log, cancel, onlyCodes);
    }

    /**
     * 按给定顺序逐只取；一只换完全部线路仍失败才记一次失败。
     *
     * @param sources   线路顺序，第一个优先
     * @param onlyCodes 只补这几只（重试 / 接着跑），null = 全部
     * @return {成功只数, 写入根数, 两轮之后仍然失败的只数}
     * @throws Exception 整条线路都不可用（一只都没成功 / 连续失败太多），调用方应当换下一条
     */
    public static int[] run(List<Min5BarSource> sources, Consumer<String> log,
                            AtomicBoolean cancel, Collection<String> onlyCodes)
            throws Exception {
        if (sources == null || sources.isEmpty()) {
            sources = allSources();
        }
        String dbDir = MountConfig.currentDbDir();
        if (dbDir == null || dbDir.trim().isEmpty()) {
            throw new IllegalStateException("数据文件夹没有设置，没法补 5 分钟线。");
        }
        List<String> codes = listCodes(new File(dbDir), onlyCodes);
        if (codes.isEmpty()) {
            throw new IllegalStateException("数据文件夹里还没有股票库，先把日线补上再来补 5 分钟线。");
        }

        report(log, "5 分钟线：在线线路逐只补齐 " + codes.size() + " 只（"
                + names(sources) + "，只补不删，库里已有的老数据一条都不动）");

        PassResult first = fetchPass(sources, codes, log, cancel);
        // 一只都没成功 = 这条线路其实不通。先看能不能自救：用户如果配了重拨命令，
        // 这时候换一次 IP 再跑一轮，比直接把这趟判死有用得多（程序自动、只此一次）。
        boolean triedRedial = false;
        if (blockedOut(first)) {
            if (Redial.available() && (cancel == null || !cancel.get())) {
                report(log, "5 分钟线：一只都没取到，判断是本机被这个站限流了");
                triedRedial = true;
                if (Redial.redialAndWait(log, cancel)) {
                    report(log, "5 分钟线：换完 IP 重新跑一轮");
                    first = fetchPass(sources, codes, log, cancel);
                }
            } else if (!Redial.available()) {
                // 没配命令就别自作主张换 IP，但要把「能这么干」这件事说出来，
                // 否则用户只会以为线路坏了，第二天照样撞同一堵墙
                report(log, "5 分钟线：一只都没取到。要是反复这样，多半是本机 IP 被限速；"
                        + "可在「路径设置 → 换 IP 命令」里记一条路由器重拨命令，"
                        + "下次这种情况程序会自动换一次 IP 再试");
            }
        }
        // 换完 IP 还是零成功才抛出去，让上层换一条线路再来，而不是当成「全部跳过」。
        // 这一步必须在第二轮之前判：真断网时不必把全市场再跑一遍。
        if (first.done == 0 && !first.failed.isEmpty()) {
            throw new IOException("在线线路没有取到任何 5 分钟数据（" + first.failed.size() + " 只全部失败"
                    + (first.blocked > 0 ? "，其中 " + first.blocked + " 只被线路直接拒绝" : "") + "）"
                    + (triedRedial ? "，已换过一次 IP 仍不行" : ""));
        }
        if (first.consecutiveMax >= GIVE_UP_AFTER) {
            throw new IOException("在线线路连续 " + first.consecutiveMax + " 只失败，判定不可用");
        }

        int done = first.done;
        int barsWritten = first.bars;
        int[] served = first.served;
        List<String> failed = first.failed;

        // 第二轮：只补第一轮没取到的。偶发断连是「这只撞上了、那只没事」，重排一遍基本都能补上，
        // 比直接把几十只记成失败更接近用户想要的「全市场都更新到」。
        if (!failed.isEmpty() && (cancel == null || !cancel.get())) {
            report(log, "5 分钟线：第一轮有 " + failed.size() + " 只没取到，再补一轮…");
            PassResult retry = fetchPass(sources, failed, log, cancel);
            done += retry.done;
            barsWritten += retry.bars;
            for (int i = 0; i < served.length; i++) {
                served[i] += retry.served[i];
            }
            failed = retry.failed;
        }

        Log.info("在线 5 分钟线完成：" + done + " 只（" + servedText(sources, served)
                + "），写入 " + barsWritten + " 根，失败 " + failed.size() + " 只");
        report(log, "5 分钟线：哪家补的 → " + servedText(sources, served));
        return new int[]{done, barsWritten, failed.size()};
    }

    /** 一轮的结果。失败的不往外抛 —— 要不要再来一轮是上层的事。 */
    private static final class PassResult {
        int done;
        int bars;
        /** 这一轮里最长的「连续失败」串，只用来判线路是不是彻底不通 */
        int consecutiveMax;
        /** 被线路直接拒绝（HTTP 403 / 429）的只数，用来区分「换源」还是「换 IP」 */
        int blocked;
        /** 每家各补了多少只（跟 sources 下标对齐） */
        int[] served;
        List<String> failed = Collections.synchronizedList(new ArrayList<String>());
    }

    /**
     * 这一轮是不是「整台机器被拒」的样子。
     *
     * <p>判据只有一条：<b>一只都没成功</b>。取到一部分就不算 —— 那说明线路是通的，
     * 只是零星几只没数据，为这个去断网重拨不值得。</p>
     */
    private static boolean blockedOut(PassResult result) {
        return result.done == 0 && !result.failed.isEmpty();
    }

    /** 这条异常链里有没有「被拒绝」的结论（各层包装过的也算） */
    private static boolean isBlocked(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof BlockedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条线路在一轮里的状态：补成功多少只、连续几次失败、这一轮是否已被摘掉。
     * 三家共用同一份，所以摘掉是「本轮的全局决定」，不是某一个线程自己的判断。
     */
    private static final class SourceState {
        final AtomicInteger served = new AtomicInteger();
        final AtomicInteger fails = new AtomicInteger();
        final AtomicBoolean disabled = new AtomicBoolean();
    }

    /** 这一轮的线路是不是全被摘掉了 */
    private static boolean allDisabled(SourceState[] states) {
        for (SourceState state : states) {
            if (!state.disabled.get()) {
                return false;
            }
        }
        return true;
    }

    /** 把一批代码逐只取一遍（并发 {@link #THREADS}），收集成败 */
    private static PassResult fetchPass(final List<Min5BarSource> sources, List<String> codes,
                                        final Consumer<String> log, AtomicBoolean cancel)
            throws Exception {
        final PassResult result = new PassResult();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger barsWritten = new AtomicInteger();
        final AtomicInteger consecutive = new AtomicInteger();
        final AtomicInteger consecutiveMax = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        final SourceState[] states = new SourceState[sources.size()];
        for (int i = 0; i < states.length; i++) {
            states[i] = new SourceState();
        }
        final int total = codes.size();
        final int reportStep = Math.max(50, total / 10);
        final List<String> failed = result.failed;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, Math.max(1, total)));
        List<Future<?>> futures = new ArrayList<Future<?>>(total);
        try {
            for (final String code : codes) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (cancel != null && cancel.get()) {
                            return;
                        }
                        try {
                            int written = fetchOne(sources, states, code, cancel, log);
                            if (written > 0) {
                                barsWritten.addAndGet(written);
                            }
                            done.incrementAndGet();
                            consecutive.set(0);
                        } catch (Exception e) {
                            failed.add(code);
                            if (isBlocked(e)) {
                                blocked.incrementAndGet();
                            }
                            int now = consecutive.incrementAndGet();
                            if (now > consecutiveMax.get()) {
                                consecutiveMax.set(now);
                            }
                            // 底层异常大多是英文原文，落日志就够；界面上的提示在
                            // Min5Http 里已经翻成中文，用户不会看到 "Unexpected end of file"
                            Log.error("在线补 5 分钟失败 " + code + "：" + e.getMessage());
                        }
                        int finished = done.get() + failed.size();
                        if (finished % reportStep == 0) {
                            report(log, "5 分钟线：已处理 " + finished + "/" + total
                                    + "，写入 " + barsWritten.get() + " 根");
                        }
                    }
                }));
            }
            long deadline = System.currentTimeMillis()
                    + TimeUnit.MINUTES.toMillis(PASS_TIMEOUT_MINUTES);
            for (Future<?> future : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new IOException("在线线路超时，没能补齐 5 分钟线");
                }
                try {
                    future.get(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (cancel != null && cancel.get()) {
            report(log, "5 分钟线：已停止，写入 " + barsWritten.get() + " 根后剩下的没跑");
        }
        result.done = done.get();
        result.bars = barsWritten.get();
        result.consecutiveMax = consecutiveMax.get();
        result.blocked = blocked.get();
        result.served = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            result.served[i] = states[i].served.get();
        }
        return result;
    }

    /**
     * 补一只：按顺序换线路，第一家给得出数据就停。
     *
     * <p>顺序里被摘掉的那几家直接跳过（见 {@link #SOURCE_GIVE_UP_AFTER}），所以
     * 「某一家彻底不通」的代价只是最前面几只白跑一次，不会拖着全市场陪它。</p>
     *
     * @return 写入的 5 分钟根数
     * @throws Exception 所有线路都失败（写库失败也算，那是库的问题，不该换个源再写一遍）
     */
    private static int fetchOne(List<Min5BarSource> sources, SourceState[] states, String code,
                                AtomicBoolean cancel, Consumer<String> log) throws Exception {
        // 全摘光了就不必再开库、也不必再等超时：剩下的会直接记失败，上层据此判线路不可用
        if (allDisabled(states)) {
            throw new IOException("在线线路全部不可用");
        }
        long lastTime;
        Connection conn = null;
        try {
            conn = DBConnection.connect(code);
            lastTime = MinDataUpdater.lastTime(conn);
        } finally {
            DBConnection.closeQuietly(conn);
        }

        Exception lastError = null;
        for (int i = 0; i < sources.size(); i++) {
            if (cancel != null && cancel.get()) {
                return 0;
            }
            SourceState state = states[i];
            if (state.disabled.get()) {
                continue;
            }
            Min5BarSource source = sources.get(i);
            List<TdxMinBar> bars;
            try {
                bars = source.fetch(code, lastTime);
            } catch (Exception e) {
                lastError = e;
                Log.error("在线补 5 分钟失败（" + source.label() + "）" + code + "：" + e.getMessage());
                if (state.fails.incrementAndGet() >= SOURCE_GIVE_UP_AFTER
                        && state.disabled.compareAndSet(false, true)) {
                    Log.error("在线补 5 分钟：本轮摘掉 " + source.id() + "（" + e.getMessage() + "）");
                    report(log, "5 分钟线：" + source.label() + " 连着 " + SOURCE_GIVE_UP_AFTER
                            + " 只取不到，这一轮先跳过它，交给后面几家");
                }
                continue;
            }
            // 这一家通了：把它的「连续失败」清零，摘掉的线路不复活（本轮内）
            state.fails.set(0);
            if (bars == null || bars.isEmpty()) {
                // 这家没给数据（停牌 / 这只它没有），换下一家试试
                continue;
            }
            state.served.incrementAndGet();
            return MinDataUpdater.writeBars(code, bars, false);
        }
        if (lastError != null) {
            throw lastError;
        }
        return 0;
    }

    /** 本地库里的代码清单（只补已有库，绝不凭空造库） */
    private static List<String> listCodes(File dir, Collection<String> onlyCodes) {
        List<String> codes = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) {
            return codes;
        }
        Set<String> allow = onlyCodes == null || onlyCodes.isEmpty()
                ? null : new HashSet<String>(onlyCodes);
        for (File file : files) {
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".db")) {
                continue;
            }
            String code = name.substring(0, name.length() - 3);
            if (code.trim().isEmpty()) {
                continue;
            }
            if (allow != null && !allow.contains(code)) {
                continue;
            }
            codes.add(code);
        }
        Collections.sort(codes);
        return codes;
    }

    /** 「东方财富 → 新浪 → 腾讯」 */
    private static String names(List<Min5BarSource> sources) {
        StringBuilder sb = new StringBuilder();
        for (Min5BarSource source : sources) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(source.label());
        }
        return sb.toString();
    }

    /** 「东方财富 3190 只 / 新浪 12 只」；一家都没补到就说实话 */
    private static String servedText(List<Min5BarSource> sources, int[] served) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < sources.size() && i < served.length; i++) {
            if (served[i] <= 0) {
                continue;
            }
            total += served[i];
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(sources.get(i).label()).append(' ').append(served[i]).append(" 只");
        }
        return total == 0 ? "没有补到" : sb.toString();
    }

    private static void report(Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
