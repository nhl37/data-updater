package updater.f10;

import updater.log.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 批量刷新全市场的股东 / 板块题材缓存。
 *
 * <p>这两个接口都只能按股票问，没有「一次给全市场」的批量版本，所以只能并发跑。
 * 一天一次的量级（5300 只 × 1~2 个请求）在 4 个线程下大约 10 分钟，因此：</p>
 * <ul>
 *   <li><b>可取消</b>：{@code cancelled} 一旦返回 true 就不再派新活，已抓到的照常落库；</li>
 *   <li><b>可续跑</b>：抓成功的写回 TTL 缓存，下次再跑直接命中缓存跳过，等于断点续跑；</li>
 *   <li><b>失败不中断</b>：单只报错只计数，不发异常（网络抖动很常见，不能因为一只把整批废掉）。</li>
 * </ul>
 *
 * <p>什么时候该调：财报导入之后（股东整批换代）。平时打开某只股票的 F10 是单只按需抓，
 * 不需要先跑一遍全市场。</p>
 */
public final class F10BulkSync {

    /** 并发度：东财这两个接口实测 4~6 线程不会被限，再高意义不大（网络往返已经打满） */
    private static final int THREADS = 4;

    private F10BulkSync() {
    }

    /** 刷新结果 */
    public record Summary(int total, int holders, int concepts, int failed, long millis,
                          int reportDate) {
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(total).append(" 只，耗时 ").append(millis / 1000).append(" 秒");
            if (holders > 0 && concepts > 0) {
                sb.append("；抓到 股东 ").append(holders).append(" 只 / 板块 ").append(concepts).append(" 只");
            } else if (holders > 0) {
                sb.append("；抓到 股东 ").append(holders).append(" 只");
            } else if (concepts > 0) {
                sb.append("；抓到 板块 ").append(concepts).append(" 只");
            }
            if (holders > 0 && reportDate > 0) {
                sb.append("（报告期 ").append(reportDate).append("）");
            }
            if (failed > 0) {
                sb.append("；失败 ").append(failed).append(" 只（下次会自动重试）");
            }
            return sb.toString();
        }
    }

    /**
     * 本地有日线库的股票代码（sh600000 这种写法）。
     *
     * <p>只取这些：没有日线库的代码（指数、基金、退市）既用不到 F10，
     * 也不在用户的任何列表里，抓了纯属浪费请求。</p>
     */
    public static List<String> localStockCodes() {
        List<String> result = new ArrayList<>();
        File dir = dbDir();
        if (dir == null || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (!name.endsWith(".db")) {
                continue;
            }
            String code = name.substring(0, name.length() - 3);
            // 只认 sh/sz/bj + 6 位数字，避免把 index_xxx.db 之类混进来
            if (code.length() != 8 || !code.substring(2).chars().allMatch(Character::isDigit)) {
                continue;
            }
            result.add(code);
        }
        return result;
    }

    private static File dbDir() {
        String path = updater.mount.MountConfig.currentDbDir();
        return path.isEmpty() ? null : new File(path);
    }

    /**
     * 并发刷新一批股票（不限期别：股东取接口最新一期）。
     *
     * @param codes    股票代码，sh/sz 前缀都行
     * @param holders  是否刷十大流通股东
     * @param concepts 是否刷板块与核心题材
     * @param progress 进度回调（已处理只数, 总只数, 当前代码），可为 null
     * @param cancelled 取消判定，可为 null
     */
    public static Summary refresh(Collection<String> codes, boolean holders, boolean concepts,
                                  Progress progress, BooleanSupplier cancelled) {
        return refresh(codes, 0, holders, concepts, progress, cancelled);
    }

    /**
     * 并发刷新一批股票，股东按指定报告期取。
     *
     * <p>「下载哪个季度的财报就更新哪个季度的股东」靠 {@code reportDate} 落地：
     * 传 {@code 20260630} 就取 2026 中报那一期。该季东财还没披露时
     * {@link TopHolderRepository} 会回落成最新一期（不报错、不中断整批）。</p>
     *
     * @param reportDate 股东报告期 yyyyMMdd；0 表示取接口最新一期
     */
    public static Summary refresh(Collection<String> codes, int reportDate,
                                  boolean holders, boolean concepts,
                                  Progress progress, BooleanSupplier cancelled) {
        long start = System.currentTimeMillis();
        // 场内基金（ETF / LOF）没有十大股东、没有板块题材，逐只请求只是白跑一遍接口
        List<String> list = new ArrayList<String>();
        for (String code : codes) {
            if (updater.util.SecurityType.isFund(code)) {
                continue;
            }
            list.add(code);
        }
        int total = list.size();
        if (total == 0) {
            return new Summary(0, 0, 0, 0, 0, reportDate);
        }
        AtomicInteger done = new AtomicInteger();
        AtomicInteger okHolder = new AtomicInteger();
        AtomicInteger okConcept = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();

        Runnable worker = () -> {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return;
                }
                int index = cursor.getAndIncrement();
                if (index >= total) {
                    return;
                }
                String code = list.get(index);
                try {
                    if (holders) {
                        TopHolderRepository.load(code, reportDate, false);
                        okHolder.incrementAndGet();
                    }
                    if (concepts) {
                        ConceptRepository.load(code, false);
                        okConcept.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    if (Log.outDebugLog) {
                        Log.out("F10 批量刷新失败 " + code + "：" + e.getMessage());
                    }
                }
                int finished = done.incrementAndGet();
                if (progress != null && (finished % 20 == 0 || finished == total)) {
                    progress.update(finished, total, code, okHolder.get() + okConcept.get());
                }
            }
        };

        Thread[] threads = new Thread[Math.min(THREADS, total)];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(worker, "f10-bulk-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Summary(total, okHolder.get(), okConcept.get(), failed.get(),
                System.currentTimeMillis() - start, reportDate);
    }

    /** 进度回调 */
    public interface Progress {
        void update(int done, int total, String code, int fetched);
    }
}
