package updater.holder;

import updater.f10.F10Api;
import updater.log.Log;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 全市场「十大流通股东」按季度同步。
 *
 * <p>数据源是东财数据中心 {@code RPT_DMSK_HOLDERS}：它<b>按报告期给全市场</b>，
 * 一个季度约 5 万行（5000 多只股票 × 前十大），每页最多 50 条 —— 也就是一千多页。
 * 页数上限改不了（试过 500 / 1000 都返回「服务器繁忙」），所以只能并发拉：
 * 实测 8~12 个线程不会触发限流，一个季度约 1 分钟，两年（8 个季度）七八分钟。</p>
 *
 * <p>这也是为什么不用 {@code F10BulkSync} 那套「按股票问」的接口：5300 只每只一个请求，
 * 一个季度就要 5300 次往返，还拿不到「持股市值」字段（要自己乘季末价）。</p>
 *
 * <p>三条与 {@code F10BulkSync} 一致的约定：<b>可取消</b>、<b>失败不中断</b>（单页失败重试 3 次，
 * 仍失败就记下来继续，最后报「缺 N 页」）、<b>写库只在调用线程</b>（SQLite 单写者，
 * 网络在池里跑，落库在同步线程串行做）。</p>
 */
public final class HolderSync {

    /** 东财这个接口每页最多 50 条，写死 */
    private static final int PAGE_SIZE = 50;

    /** 并发度：实测 8 线程稳定，再高容易触发「服务器繁忙」 */
    private static final int THREADS = 8;

    /** 批量获取时的读超时：8 个线程一起排队，服务端会比单请求慢不少，8 秒不够用 */
    private static final int TIMEOUT_MS = 20000;

    private static final String URL = "https://datacenter-web.eastmoney.com/api/data/v1/get"
            + "?reportName=RPT_DMSK_HOLDERS&columns=ALL"
            + "&pageSize=" + PAGE_SIZE + "&pageNumber=%d"
            + "&sortColumns=SECURITY_CODE,RANK&sortTypes=1,1"
            + "&source=WEB&client=WEB&filter=%s";

    /** 东财数据中心要求带 Referer，否则会被拒 */
    private static final String REFERER = "https://data.eastmoney.com/";

    /** 只要流通 A 股：限售股、B 股、H 股不在「流通大股东」口径里 */
    private static final String FILTER = "(END_DATE='%s')(SHARES_TYPE=\"流通A股\")";

    private HolderSync() {
    }

    /** 同步进度回调（在后台线程调用，界面要自己 invokeLater） */
    public interface Progress {
        /**
         * @param reportDate 当前季度
         * @param done       已完成页数
         * @param total      总页数
         * @param rows       已入库行数
         */
        void onPage(int reportDate, int done, int total, int rows);

        /** 阶段提示，例如「2026-09-30 没有数据，跳过」 */
        default void onMessage(String message) {
        }
    }

    /** 单个季度的同步结果 */
    public record Result(int reportDate, int rows, int stocks, int failedPages,
                         boolean cancelled, String error) {

        public boolean ok() {
            return error == null && !cancelled && failedPages == 0;
        }

        public String text() {
            if (error != null) {
                return periodText(reportDate) + " 同步失败：" + error;
            }
            if (cancelled) {
                return periodText(reportDate) + " 已取消（已入库 " + rows + " 行）";
            }
            String base = periodText(reportDate) + " 入库 " + rows + " 行 / " + stocks + " 只";
            return failedPages > 0 ? base + "（缺 " + failedPages + " 页，可重跑补齐）" : base;
        }
    }

    // ===================== 季度 =====================

    /** 最近的若干个季末（yyyyMMdd），按时间倒序，第一个是当前最新已结束的季度 */
    public static List<Integer> recentQuarters(int count) {
        List<Integer> list = new ArrayList<>();
        LocalDate date = latestQuarterEnd(LocalDate.now());
        for (int i = 0; i < count; i++) {
            list.add(date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth());
            date = previousQuarterEnd(date);
        }
        return list;
    }

    /** 今天之前（含今天）最近的一个季末 */
    public static LocalDate latestQuarterEnd(LocalDate today) {
        int year = today.getYear();
        LocalDate[] ends = {
                LocalDate.of(year, 3, 31),
                LocalDate.of(year, 6, 30),
                LocalDate.of(year, 9, 30),
                LocalDate.of(year, 12, 31)};
        LocalDate found = LocalDate.of(year - 1, 12, 31);
        for (LocalDate end : ends) {
            if (!end.isAfter(today)) {
                found = end;
            }
        }
        return found;
    }

    /** 上一个季末 */
    public static LocalDate previousQuarterEnd(LocalDate end) {
        int month = end.getMonthValue();
        int year = end.getYear();
        if (month == 3) {
            return LocalDate.of(year - 1, 12, 31);
        }
        if (month == 6) {
            return LocalDate.of(year, 3, 31);
        }
        if (month == 9) {
            return LocalDate.of(year, 6, 30);
        }
        return LocalDate.of(year, 9, 30);
    }

    /** 报告期 yyyyMMdd → 「2026三季报」这种叫法 */
    public static String periodText(int reportDate) {
        int year = reportDate / 10000;
        switch (reportDate % 10000) {
            case 331:
                return year + "一季报";
            case 630:
                return year + "中报";
            case 930:
                return year + "三季报";
            case 1231:
                return year + "年报";
            default:
                return String.valueOf(reportDate);
        }
    }

    /** 报告期 yyyyMMdd → 2026-09-30 */
    public static String periodDate(int reportDate) {
        String text = String.valueOf(reportDate);
        if (text.length() != 8) {
            return text;
        }
        return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8);
    }

    // ===================== 同步 =====================

    /** 该季度在东财有多少行（用来算总页数）；0 表示该季还没有披露 */
    public static int remoteCount(int reportDate) throws Exception {
        String url = String.format(URL, 1, filter(reportDate));
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                JSONObject root = JSONObject.parseObject(F10Api.get(url, REFERER, TIMEOUT_MS));
                JSONObject result = root == null ? null : root.getJSONObject("result");
                return result == null ? 0 : result.getIntValue("count");
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    Thread.sleep(500L * attempt);
                }
            }
        }
        throw last == null ? new IllegalStateException("读取季度规模失败") : last;
    }

    /**
     * 同步一个季度：先清空该季旧数据，再按页并发拉取并落库。
     *
     * @param cancelled 返回 true 时不再派新页，已抓到的照常入库
     */
    public static Result sync(int reportDate, HolderStore store, Progress progress,
                              BooleanSupplier cancelled) {
        int count;
        try {
            count = remoteCount(reportDate);
        } catch (Exception e) {
            return new Result(reportDate, 0, 0, 0, false, describe(e));
        }
        if (count <= 0) {
            return new Result(reportDate, 0, 0, 0, false, null);
        }
        int pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
        try {
            store.deletePeriod(reportDate);
        } catch (Exception e) {
            return new Result(reportDate, 0, 0, 0, false, "清空旧数据失败：" + describe(e));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread thread = new Thread(r, "holder-sync");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<List<HolderRow>> completion =
                new ExecutorCompletionService<>(pool);
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int rows = 0;
        int stocks = 0;
        boolean stopped = false;
        try {
            for (int page = 1; page <= pages; page++) {
                final int p = page;
                completion.submit(new Callable<List<HolderRow>>() {
                    @Override
                    public List<HolderRow> call() {
                        return fetchPage(reportDate, p);
                    }
                });
                submitted.incrementAndGet();
            }
            int finished = 0;
            while (finished < submitted.get()) {
                Future<List<HolderRow>> future = completion.take();
                finished++;
                List<HolderRow> page;
                try {
                    page = future.get();
                } catch (Exception e) {
                    page = Collections.emptyList();
                }
                if (page == null || page.isEmpty()) {
                    failed.incrementAndGet();
                } else {
                    for (HolderRow row : page) {
                        if (!row.code().isEmpty()) {
                            rows++;
                        }
                    }
                    try {
                        store.insert(reportDate, page);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        Log.out("warn ##### 股东数据写入失败 " + periodDate(reportDate)
                                + " 第 " + finished + " 页：" + e.getMessage());
                    }
                }
                if (progress != null) {
                    progress.onPage(reportDate, finished, pages, rows);
                }
                if (cancelled != null && cancelled.getAsBoolean() && !stopped) {
                    stopped = true;
                    pool.shutdownNow();
                    return finish(store, reportDate, rows, failed.get(), true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(store, reportDate, rows, failed.get(), true);
        } finally {
            pool.shutdownNow();
        }
        try {
            store.finishPeriod(reportDate);
        } catch (Exception e) {
            return new Result(reportDate, rows, 0, failed.get(), false, "写汇总失败：" + describe(e));
        }
        try {
            stocks = store.stockCount(reportDate);
            // 用落库后的真实行数：接口分页偶尔有重叠，主键去重会比收到的条数少几百
            rows = store.rowCount(reportDate);
        } catch (Exception ignored) {
            // 统计失败不影响数据本身
        }
        return new Result(reportDate, rows, stocks, failed.get(), false, null);
    }

    private static Result finish(HolderStore store, int reportDate, int rows,
                                 int failed, boolean cancelled) {
        int stocks = 0;
        try {
            store.finishPeriod(reportDate);
            stocks = store.stockCount(reportDate);
            rows = store.rowCount(reportDate);
        } catch (Exception ignored) {
            // 取消时也要把已入库的部分统计上，统计失败就算了
        }
        return new Result(reportDate, rows, stocks, failed, cancelled, null);
    }

    /** 拉一页，失败重试 3 次（指数退避）；仍失败返回空列表，由调用方记缺页 */
    private static List<HolderRow> fetchPage(int reportDate, int page) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String url = String.format(URL, page, filter(reportDate));
                JSONObject root = JSONObject.parseObject(F10Api.get(url, REFERER, TIMEOUT_MS));
                JSONObject result = root == null ? null : root.getJSONObject("result");
                JSONArray data = result == null ? null : result.getJSONArray("data");
                List<HolderRow> rows = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        if (item != null) {
                            rows.add(HolderRow.parse(reportDate, item));
                        }
                    }
                }
                return rows;
            } catch (Exception e) {
                if (attempt == 3) {
                    return Collections.emptyList();
                }
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Collections.emptyList();
                }
            }
        }
        return Collections.emptyList();
    }

    private static String filter(int reportDate) throws Exception {
        return URLEncoder.encode(String.format(FILTER, periodDate(reportDate)), StandardCharsets.UTF_8);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
