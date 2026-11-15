package updater.main.updater;

import updater.db.VaultDb;
import updater.f10.F10BulkSync;
import updater.f10.F10Store;
import updater.fetchdata.tdx.finance.FinanceImportService;
import updater.mount.MountConfig;
import updater.util.ItemProgress;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 「财报与股东」这一组的查缺补漏：按<b>应披露的报告期</b>逐季对账。
 *
 * <p>为什么按季度而不是按股票：财报是整季整批下载入库的，一次下载失败就是
 * 整季 5000 多只一起缺；股东同理，是「某一期全市场刷一遍」。所以台账一行
 * 就是一个季度，写着这一季财报有几只、股东缓存有几只，缺哪块一眼看到。</p>
 *
 * <p><b>应有清单怎么来</b>：按法定披露截止日推算（一季报 4/30、中报 8/31、
 * 三季报 10/31、年报次年 4/30），只列「到今天为止已经应该披露」的季度，
 * 不会把还没到披露期的 20261231 算成缺失，也不要求联网。范围由界面的
 * 「检查范围」限定，默认最近 3 年 —— 老季度本来就没人下载，全列出来只会
 * 让修复变成几十个季度的联网任务。</p>
 *
 * <p><b>齐不齐怎么判</b>：以本地「单季财报条数的最大值」为应有只数，
 * 达到 90% 才算齐。整季没导（0 条）、只导了一半都会标出来。股东看
 * f10.db 里 {@code holder:<报告期>} 这个桶的股票数；只有最新一季允许用
 * 不限期别的 {@code holder} 通用桶兜底（那个桶存的正是接口最新一期）。</p>
 *
 * <p>修复：缺财报的季度走现成的 {@link FinanceImportService} 重新下载并覆盖导入；
 * 缺股东的季度只清自己那一季的桶再全市场刷新，<b>不调 F10SyncDialog.refreshHolders</b>
 * —— 那个方法会无条件清掉所有季度的桶，逐季补的时候会把刚补好的抹掉。</p>
 *
 * <p><b>修复粒度（2026-11-08 起）</b>：只有「数量级缺口」（整季没导、只导了一半）才整季重导 /
 * 清桶全市场重刷。数量已经在阈值以上、只是少几只的情况，按代码精确补：
 * 财报对 {@code (code, report_date)} 主键单条 {@code INSERT OR REPLACE} 那几行，
 * 股东只 {@code refresh(那几只)}。判定「缺哪几只」的基准是「最近一年出现过的代码」，
 * 不是本地日线库那份代码表 —— 后者会把东财本来就没有数据的标的算成永远补不上的假缺失。</p>
 */
public final class FinanceIntegrityChecker {

    /** 达到应有只数的这个比例才算齐（导了一半不算齐） */
    private static final double FULL_RATIO = 0.9;
    /** 没配范围时默认看最近几年 */
    public static final int DEFAULT_YEARS = 3;

    private FinanceIntegrityChecker() {
    }

    /** 一次对账 / 一次修复的汇总，用来写结果行 */
    public static final class Summary {
        public int total;
        /** 财报和股东都齐的季度 */
        public int ok;
        public int missingFinance;
        public int missingHolders;
        /** 修复时才有的计数 */
        public int financeFixed;
        public int holdersFixed;
        /** 按代码补录的财报行数 / 股东只数（只补几只那种，不动整季） */
        public long financeOnlyRows;
        public int holderOnlyRows;
        public int failed;
        public long rowsWritten;
        public long elapsedMs;

        public String describeScan() {
            StringBuilder sb = new StringBuilder();
            sb.append("核对 ").append(total).append(" 个季度：齐全 ").append(ok);
            int bad = total - ok;
            if (bad > 0) {
                sb.append("，有问题 ").append(bad).append(" 季（缺财报 ").append(missingFinance)
                        .append("、缺股东 ").append(missingHolders).append("）")
                        .append("（点「修复缺失」只补差的那几只）");
            } else {
                sb.append("，没有发现缺失");
            }
            sb.append(" · 用时 ").append(formatDuration(elapsedMs));
            return sb.toString();
        }

        public String describeRepair() {
            StringBuilder sb = new StringBuilder();
            sb.append("修复完成：财报 ").append(financeFixed).append(" 季");
            if (rowsWritten > 0) {
                sb.append("（整季重导 ").append(rowsWritten).append(" 条）");
            }
            if (financeOnlyRows > 0) {
                sb.append("（按代码补 ").append(financeOnlyRows).append(" 只）");
            }
            sb.append("，股东 ").append(holdersFixed).append(" 季");
            if (holderOnlyRows > 0) {
                sb.append("（按代码补 ").append(holderOnlyRows).append(" 只）");
            }
            if (failed > 0) {
                sb.append("，失败 ").append(failed).append(" 项");
            }
            sb.append(" · 用时 ").append(formatDuration(elapsedMs));
            return sb.toString();
        }
    }

    /** 一个季度的对账结果 */
    private static final class Quarter {
        String date;
        int financeRows;
        int holderRows;
        /** 股东数是从分季桶还是通用桶来的，写进台账免得误会 */
        boolean holderFromLatest;
        int baseline;
        /** 数量够、只是比「最近一年出现过的股票」少这几只：只补这几只，不整季重导 */
        final List<String> missingFinance = new ArrayList<String>();
        final List<String> missingHolder = new ArrayList<String>();

        int threshold() {
            return Math.max(1, (int) Math.ceil(baseline * FULL_RATIO));
        }

        /** 数量级缺口：整季没导、只导了一半。这种只能整季重导 / 全市场重刷 */
        boolean financeShort() {
            return financeRows < threshold();
        }

        boolean holderShort() {
            return holderRows < threshold();
        }

        /**
         * 该不该整季重刷（而不是只补缺的几只）。
         *
         * <p>用了通用桶兜底说明分季桶缺得太多，逐只补要跑几千次，不如清桶整季重刷；
         * 数量已经在阈值以上、只是少几只的情况才走 {@link #missingHolder} 逐只补。</p>
         */
        boolean holderNeedsFullRefresh() {
            return holderFromLatest || holderShort();
        }

        /** 有没有问题：数量不够，或者数量够但具体缺了几只 */
        boolean financeMissing() {
            return financeShort() || !missingFinance.isEmpty();
        }

        boolean holdersMissing() {
            return holderShort() || !missingHolder.isEmpty();
        }

        boolean clean() {
            return !financeMissing() && !holdersMissing();
        }

        String detail() {
            return "财报 " + financeRows + " 只" + tail(missingFinance)
                    + " · 股东 " + holderRows + " 只" + tail(missingHolder)
                    + (holderFromLatest ? "（通用桶）" : "") + " · 应有约 " + baseline + " 只";
        }

        String problem() {
            StringBuilder sb = new StringBuilder();
            if (financeMissing()) {
                if (financeShort()) {
                    sb.append(financeRows == 0 ? "整季没有财报" : "财报只有 " + financeRows + " 只")
                            .append("（应有约 ").append(baseline).append("）");
                } else {
                    sb.append("缺财报 ").append(missingFinance.size()).append(" 只：")
                            .append(sample(missingFinance));
                }
            }
            if (holdersMissing()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                if (holderShort()) {
                    sb.append(holderRows == 0 ? "整季没有十大流通股东" : "股东只有 " + holderRows + " 只")
                            .append("（应有约 ").append(baseline).append("）");
                } else {
                    sb.append("缺股东 ").append(missingHolder.size()).append(" 只：")
                            .append(sample(missingHolder));
                }
            }
            if (sb.length() == 0) {
                sb.append(detail());
            }
            return sb.toString();
        }

        /** 台账里最多列 6 个代码，剩下的折成「等 N 只」 */
        private static String tail(List<String> codes) {
            return codes.isEmpty() ? "" : "（缺 " + codes.size() + "）";
        }

        private static String sample(List<String> codes) {
            int limit = Math.min(codes.size(), 6);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                sb.append(codes.get(i));
            }
            if (codes.size() > limit) {
                sb.append(" 等 ").append(codes.size()).append(" 只");
            }
            return sb.toString();
        }
    }

    // ================================================================== 对账

    /**
     * 逐季对账，结论写进台账（齐全的记「完成」，有缺的记「失败」）。
     *
     * @param years 只看最近几年（1/3/5/10），小于等于 0 用 {@link #DEFAULT_YEARS}
     */
    public static Summary scan(final Consumer<String> log, final ItemProgress items,
                               final AtomicBoolean cancel, final Collection<String> onlyCodes,
                               final int years) throws Exception {
        long start = System.currentTimeMillis();
        final Summary summary = new Summary();
        final List<String> quarters = quartersToCheck(years, onlyCodes);
        final Map<String, Integer> finance = financeCounts();
        final int baseline = baseline(finance);
        final String newest = quarters.isEmpty() ? "" : quarters.get(quarters.size() - 1);

        report(log, "财报库: " + financeDbPath());
        report(log, "股东缓存: " + F10Store.dbFilePath());
        report(log, "应披露 " + quarters.size() + " 个季度（最近 " + normalizedYears(years)
                + " 年，按披露截止日推算）；单季应有约 " + baseline + " 只");

        items.planned(quarters);
        int genericHolders = safeCount(F10Store.holderKind(0));
        Set<String> financeUniverse = safeFinanceUniverse(quarters);
        Set<String> holderUniverse = safeHolderUniverse(quarters);
        Map<String, Set<String>> codeCache = new HashMap<String, Set<String>>();
        for (String quarter : quarters) {
            if (cancel != null && cancel.get()) {
                break;
            }
            items.begin(quarter);
            try {
                Quarter q = inspect(quarter, finance, baseline, genericHolders,
                        quarter.equals(newest), financeUniverse, holderUniverse, codeCache);
                if (q.clean()) {
                    summary.ok++;
                    items.done(quarter, q.detail());
                } else {
                    if (q.financeMissing()) {
                        summary.missingFinance++;
                    }
                    if (q.holdersMissing()) {
                        summary.missingHolders++;
                    }
                    items.fail(quarter, q.problem());
                }
            } catch (Throwable t) {
                items.fail(quarter, "核对失败：" + oneLine(t));
            }
        }
        summary.total = quarters.size();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeScan());
        return summary;
    }

    // ================================================================== 修复

    /**
     * 按台账里标了问题的季度补齐。
     *
     * <p>每季重新对账一次再补，不复用上一轮扫描结果：从隔天的历史台账点修复，
     * 补的也是「现在真正缺的」。</p>
     *
     * @param refreshHolders 是否顺带补该季十大流通股东；只补缺的那几只，只有分季桶缺太多才全市场重刷
     */
    public static Summary repair(final Consumer<String> log, final ItemProgress items,
                                 final AtomicBoolean cancel, final Collection<String> onlyCodes,
                                 final int years, final boolean refreshHolders) throws Exception {
        long start = System.currentTimeMillis();
        final Summary summary = new Summary();
        final List<String> quarters = quartersToCheck(years, onlyCodes);
        final Map<String, Integer> finance = financeCounts();
        final int baseline = baseline(finance);
        final String newest = quarters.isEmpty() ? "" : quarters.get(quarters.size() - 1);
        final List<String> codes = F10BulkSync.localStockCodes();

        report(log, "开始修复 " + quarters.size() + " 个季度"
                + "（只补查出来缺的那几只；只有整季没导/只导一半才整季重导）"
                + (refreshHolders ? "" : "，本次不刷股东"));
        items.planned(quarters);
        int genericHolders = safeCount(F10Store.holderKind(0));
        Set<String> financeUniverse = safeFinanceUniverse(quarters);
        Set<String> holderUniverse = safeHolderUniverse(quarters);
        Map<String, Set<String>> codeCache = new HashMap<String, Set<String>>();

        for (final String quarter : quarters) {
            if (cancel != null && cancel.get()) {
                break;
            }
            items.begin(quarter);
            final Quarter before = inspect(quarter, finance, baseline, genericHolders,
                    quarter.equals(newest), financeUniverse, holderUniverse, codeCache);
            StringBuilder note = new StringBuilder();
            boolean changed = false;
            // 整季重导过 → 股东数据整批换代，必须全量重刷；只按代码补几只就不用
            boolean financeReloaded = false;

            if (before.financeShort()) {
                try {
                    FinanceImportService.PrepareResult prepared =
                            FinanceImportService.prepareByInput(quarter);
                    FinanceImportService.ImportResult result = FinanceImportService.importReport(
                            prepared, true, new Consumer<String>() {
                                @Override
                                public void accept(String message) {
                                    report(log, "[" + quarter + "] " + message);
                                }
                            });
                    summary.financeFixed++;
                    summary.rowsWritten += result.rows;
                    changed = true;
                    financeReloaded = true;
                    note.append("整季重导 ").append(result.rows).append(" 条；");
                } catch (Throwable t) {
                    summary.failed++;
                    items.fail(quarter, "财报补齐失败：" + oneLine(t));
                    report(log, "[" + quarter + "] 财报补齐失败：" + oneLine(t));
                    continue;
                }
            } else if (!before.missingFinance.isEmpty()) {
                // 数量够、只是少这几只：按主键单条替换，不删整季、不重写其它股票
                try {
                    FinanceImportService.ImportResult result = FinanceImportService.importCodes(
                            quarter, before.missingFinance, new Consumer<String>() {
                                @Override
                                public void accept(String message) {
                                    report(log, "[" + quarter + "] " + message);
                                }
                            });
                    summary.financeOnlyRows += result.rows;
                    if (result.rows > 0) {
                        summary.financeFixed++;
                    }
                    changed = true;
                    note.append("按代码补财报 ").append(result.rows).append(" 只");
                    if (result.rows < before.missingFinance.size()) {
                        note.append("（另外 ").append(before.missingFinance.size() - result.rows)
                                .append(" 只在财报包里没有，多半本来就没披露，补不回来）");
                    }
                    note.append("；");
                } catch (Throwable t) {
                    summary.failed++;
                    report(log, "[" + quarter + "] 按代码补财报失败：" + oneLine(t));
                    note.append("补财报失败：").append(oneLine(t)).append("；");
                }
            } else {
                note.append("财报已齐；");
            }

            if (refreshHolders) {
                final int reportDate = quarterInt(quarter);
                if (reportDate <= 0) {
                    note.append("报告期格式不对");
                } else if (before.holderNeedsFullRefresh() || financeReloaded) {
                    if (codes.isEmpty()) {
                        note.append("没有可刷新的股票清单");
                    } else {
                        // 只作废这一季的桶：F10SyncDialog.refreshHolders 会清掉所有季桶，
                        // 逐季补的时候用它会把前面刚补好的季度一起抹掉
                        F10Store.invalidateKind(F10Store.holderKind(reportDate));
                        F10BulkSync.Summary refreshed = F10BulkSync.refresh(codes, reportDate,
                                true, false, new F10BulkSync.Progress() {
                                    @Override
                                    public void update(int done, int total, String code, int fetched) {
                                        if (done % 500 == 0 || done == total) {
                                            report(log, "[" + quarter + "] 股东 " + done + "/" + total
                                                    + "，已抓到 " + fetched + " 只");
                                        }
                                    }
                                }, cancelSupplier(cancel));
                        summary.holdersFixed++;
                        changed = true;
                        note.append("股东全市场重刷 ").append(refreshed.holders()).append(" 只");
                        if (refreshed.failed() > 0) {
                            note.append("（失败 ").append(refreshed.failed()).append(" 只，下次自动重试）");
                        }
                    }
                } else if (!before.missingHolder.isEmpty()) {
                    // 只缺这几只：按代码逐个抓，不清桶、不碰其它股票
                    F10BulkSync.Summary refreshed = F10BulkSync.refresh(
                            before.missingHolder, reportDate, true, false, null, cancelSupplier(cancel));
                    summary.holdersFixed++;
                    summary.holderOnlyRows += refreshed.holders();
                    changed = true;
                    note.append("按代码刷股东 ").append(refreshed.holders()).append(" 只");
                    if (refreshed.failed() > 0) {
                        note.append("（失败 ").append(refreshed.failed()).append(" 只，下次自动重试）");
                    }
                } else if (before.holderFromLatest) {
                    note.append("股东暂用通用桶兜底（该季分季数据还缺，重刷本季可转正）");
                } else {
                    note.append("股东已齐");
                }
            } else if (before.holdersMissing()) {
                note.append("股东仍缺（本次未勾选刷新）");
            } else {
                note.append("股东已齐");
            }

            if (changed) {
                items.done(quarter, note.toString());
            } else {
                items.skip(quarter, "本来就齐全 · " + before.detail());
            }
        }

        summary.total = quarters.size();
        summary.elapsedMs = System.currentTimeMillis() - start;
        report(log, summary.describeRepair());
        return summary;
    }

    // ================================================================== 季度清单

    /**
     * 应披露的报告期（yyyyMMdd，升序）。
     *
     * <p>不算还没到法定截止日的季度，也不算范围外的老季度 —— 老季度用户本来就没下载，
     * 全列出来只会让「一键修复」变成几十个季度的联网任务。</p>
     */
    public static List<String> expectedQuarters(int years) {
        int span = normalizedYears(years);
        LocalDate today = LocalDate.now();
        int firstYear = today.getYear() - span + 1;
        List<String> out = new ArrayList<String>();
        for (int year = firstYear; year <= today.getYear(); year++) {
            addIfDisclosed(out, year, 3, 31, LocalDate.of(year, 4, 30), today);
            addIfDisclosed(out, year, 6, 30, LocalDate.of(year, 8, 31), today);
            addIfDisclosed(out, year, 9, 30, LocalDate.of(year, 10, 31), today);
            addIfDisclosed(out, year, 12, 31, LocalDate.of(year + 1, 4, 30), today);
        }
        return out;
    }

    private static void addIfDisclosed(List<String> out, int year, int month, int day,
                                       LocalDate deadline, LocalDate today) {
        if (!deadline.isAfter(today)) {
            out.add(String.format("%04d%02d%02d", year, month, day));
        }
    }

    /** 重试 / 修复 / 续跑时只处理台账给的季度；否则按范围生成 */
    private static List<String> quartersToCheck(int years, Collection<String> onlyCodes) {
        List<String> out;
        if (onlyCodes == null || onlyCodes.isEmpty()) {
            out = expectedQuarters(years);
        } else {
            Set<String> unique = new LinkedHashSet<String>(onlyCodes);
            out = new ArrayList<String>(unique);
            Collections.sort(out);
        }
        return out;
    }

    private static int normalizedYears(int years) {
        return years <= 0 ? DEFAULT_YEARS : Math.min(years, 30);
    }

    // ================================================================== 本地数据

    /** 本地财报库里每个报告期各有多少只 */
    private static Map<String, Integer> financeCounts() throws Exception {
        String path = financeDbPath();
        if (path.isEmpty()) {
            throw new IllegalStateException("还没有挂载财报库（finance.db），请先在「路径设置」里配好数据文件夹");
        }
        File file = new File(path);
        Map<String, Integer> counts = new HashMap<String, Integer>();
        if (!file.isFile()) {
            // 库还没有：所有季度都算缺，修复时会由导入流程建库
            return counts;
        }
        Connection conn = VaultDb.openPlain(file);
        try {
            Statement stmt = conn.createStatement();
            try {
                ResultSet rs = stmt.executeQuery(
                        "SELECT report_date, COUNT(*) FROM T_D_Finance GROUP BY report_date");
                try {
                    while (rs.next()) {
                        String date = rs.getString(1);
                        if (date != null && !date.trim().isEmpty()) {
                            counts.put(date.trim(), Integer.valueOf(rs.getInt(2)));
                        }
                    }
                } finally {
                    rs.close();
                }
            } catch (SQLException e) {
                // 表还没建 / 库打不开：按「全缺」处理，但不能装作一切正常
                throw new IllegalStateException("财报库读不出来：" + e.getMessage(), e);
            } finally {
                stmt.close();
            }
        } finally {
            conn.close();
        }
        return counts;
    }

    /**
     * 单季应有只数 = 本地各季财报条数的最大值。
     *
     * <p>不拿服务器清单当基准：那要联网、且里面常混着下一季的占位文件。
     * 本地已经导得最全的那一季就是最实在的参照，整个财报库空了才退回股票库数量。</p>
     */
    private static int baseline(Map<String, Integer> finance) {
        int max = 0;
        for (Integer value : finance.values()) {
            if (value != null && value.intValue() > max) {
                max = value.intValue();
            }
        }
        if (max <= 0) {
            max = F10BulkSync.localStockCodes().size();
        }
        return Math.max(1, max);
    }

    private static Quarter inspect(String quarter, Map<String, Integer> finance,
                                   int baseline, int genericHolders, boolean newest,
                                   Set<String> financeUniverse, Set<String> holderUniverse,
                                   Map<String, Set<String>> codeCache) {
        Quarter q = new Quarter();
        q.date = quarter;
        q.baseline = baseline;
        Integer rows = finance.get(quarter);
        q.financeRows = rows == null ? 0 : rows.intValue();
        int reportDate = quarterInt(quarter);
        q.holderRows = safeCount(F10Store.holderKind(reportDate));
        if (q.holderRows < q.threshold() && newest && genericHolders > q.holderRows) {
            // 通用桶里存的是接口最新一期；只有最新一季能拿它兜底，历史季度不算
            q.holderRows = genericHolders;
            q.holderFromLatest = true;
        }
        // 数量够才细算「缺哪几只」：整季没导时列几千个代码没意义，直接走整季重导
        if (!q.financeShort() && !financeUniverse.isEmpty()) {
            Set<String> actual = codesOf(quarter, codeCache);
            for (String code : financeUniverse) {
                if (!actual.contains(code)) {
                    q.missingFinance.add(code);
                }
            }
            Collections.sort(q.missingFinance);
        }
        if (!q.holderShort() && !q.holderFromLatest && !holderUniverse.isEmpty()) {
            Set<String> actual = F10Store.cachedCodes(F10Store.holderKind(reportDate));
            for (String code : holderUniverse) {
                if (!actual.contains(code)) {
                    q.missingHolder.add(code);
                }
            }
            Collections.sort(q.missingHolder);
        }
        return q;
    }

    /** 某季财报库里已有的代码（带前缀），按季缓存，避免逐季重复查库 */
    private static Set<String> codesOf(String quarter, Map<String, Set<String>> cache) {
        Set<String> hit = cache.get(quarter);
        if (hit != null) {
            return hit;
        }
        Set<String> codes = new LinkedHashSet<String>();
        String path = financeDbPath();
        File file = new File(path);
        if (path.isEmpty() || !file.isFile()) {
            cache.put(quarter, codes);
            return codes;
        }
        try {
            Connection conn = VaultDb.openPlain(file);
            try {
                Statement stmt = conn.createStatement();
                try {
                    ResultSet rs = stmt.executeQuery("SELECT " + "code" + " FROM T_D_Finance"
                            + " WHERE report_date = '" + quarter.replace("'", "") + "'");
                    try {
                        while (rs.next()) {
                            String code = rs.getString(1);
                            if (code != null && !code.trim().isEmpty()) {
                                codes.add(code.trim());
                            }
                        }
                    } finally {
                        rs.close();
                    }
                } finally {
                    stmt.close();
                }
            } finally {
                conn.close();
            }
        } catch (Exception ignored) {
            // 库坏了/表还没建：这一季算「没有清单」，不影响数量判定
        }
        cache.put(quarter, codes);
        return codes;
    }

    /**
     * 财报「应有清单」：最近一年在财报库里出现过的全部代码。
     *
     * <p>不拿本地日线库的代码表当基准——财报库里从来没出现过的代码（次新、未披露、退市）
     * 本来就不该有财报，用日线库求差会差出一堆永远补不上的假缺失。</p>
     */
    private static Set<String> safeFinanceUniverse(List<String> quarters) {
        Set<String> out = new LinkedHashSet<String>();
        String path = financeDbPath();
        File file = new File(path);
        if (path.isEmpty() || !file.isFile()) {
            return out;
        }
        String from = universeFrom(quarters);
        try {
            Connection conn = VaultDb.openPlain(file);
            try {
                Statement stmt = conn.createStatement();
                try {
                    ResultSet rs = stmt.executeQuery("SELECT DISTINCT code FROM T_D_Finance"
                            + (from.isEmpty() ? "" : " WHERE report_date >= '" + from + "'"));
                    try {
                        while (rs.next()) {
                            String code = rs.getString(1);
                            if (code != null && !code.trim().isEmpty()) {
                                out.add(code.trim());
                            }
                        }
                    } finally {
                        rs.close();
                    }
                } finally {
                    stmt.close();
                }
            } finally {
                conn.close();
            }
        } catch (Exception ignored) {
            // 读不到就当没有基准，退回「只看数量」的老判据
        }
        return out;
    }

    /** 股东「应有清单」：最近一年各个分季桶里出现过的 6 位代码 */
    private static Set<String> safeHolderUniverse(List<String> quarters) {
        String from = universeFrom(quarters);
        try {
            return F10Store.cachedCodesSince("holder",
                    from.isEmpty() ? null : F10Store.holderKind(quarterInt(from)));
        } catch (Throwable t) {
            return new LinkedHashSet<String>();
        }
    }

    /** 基准起点：最早要检查的那一季往前一年 */
    private static String universeFrom(List<String> quarters) {
        if (quarters == null || quarters.isEmpty()) {
            return "";
        }
        try {
            LocalDate first = LocalDate.parse(quarters.get(0),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return first.minusYears(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return "";
        }
    }

    private static java.util.function.BooleanSupplier cancelSupplier(final AtomicBoolean cancel) {
        return new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return cancel != null && cancel.get();
            }
        };
    }

    private static int safeCount(String kind) {
        try {
            return F10Store.countCache(kind);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String financeDbPath() {
        String path = MountConfig.currentFinanceDbFile();
        return path == null ? "" : path.trim();
    }

    private static int quarterInt(String quarter) {
        try {
            return Integer.parseInt(quarter);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================== 工具

    private static void report(Consumer<String> log, String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + " 秒";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        }
        return (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分";
    }

    private static String oneLine(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
