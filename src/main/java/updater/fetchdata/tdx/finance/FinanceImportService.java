package updater.fetchdata.tdx.finance;

import updater.db.FinanceDBManager;
import updater.fetchdata.capital.ShareCapitalSyncService;
import updater.finance.ShareCapitalRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报导入服务：供界面（MainFrame）调用
 *
 * 典型用法(两段式，便于中途弹框确认是否覆盖):
 *   1. PrepareResult prep = FinanceImportService.prepareByInput("202603");
 *      或 PrepareResult prep = FinanceImportService.prepareLatest();
 *   2. ImportResult result = FinanceImportService.importReport(prep, prep.hasLocalData());
 */
public class FinanceImportService {

    private FinanceImportService() {
    }

    /**
     * 检查阶段结果
     */
    public static class PrepareResult {
        /** 归一化后的报告期 yyyyMMdd */
        public String reportDate;
        /** 服务器上的财报文件名，如 gpcw20260930.zip */
        public String sourceFile;
        /** 文件大小(字节) */
        public long fileSize;
        /** 本地该报告期已有记录数 */
        public long localRowCount;
        /** 本地导入日志状态是否为 DONE */
        public boolean localDone;
        /** 服务器可用报告期(逗号分隔) */
        public String availableDates;

        /** 本地是否已有该季度财报数据 */
        public boolean hasLocalData() {
            return localRowCount > 0 || localDone;
        }
    }

    /**
     * 导入阶段结果
     */
    public static class ImportResult {
        public String reportDate;
        public String sourceFile;
        public String csvFile;
        public long fileSize;
        public int rows;
        public int columns;
        public boolean overwritten;
        /** 是否触发了股本 / 股东数据的检查（只有最新一季才会触发） */
        public boolean capitalChecked;
        /** 本次写入的股本数据行数 */
        public int capitalRows;
        /** 股本数据检查结果说明，给界面显示 */
        public String capitalMessage = "";
        /**
         * 本次导入的报告期是不是财报库里最新的一期。
         *
         * <p>决定「板块数据要不要跟着刷」：板块只在库内最新一期之后才会变，
         * 补导入更老的季度（比如库里已有 20260930，又补 20260630）时不必刷。</p>
         */
        public boolean latestReport;
        /** 导入后库里最大的报告期（yyyyMMdd），给界面/日志用；库为空时是空串 */
        public String latestReportDate = "";
    }

    /**
     * 检查最新财报报告期
     */
    public static PrepareResult prepareLatest() throws Exception {
        return prepare(null);
    }

    /**
     * 检查指定季度(如 202603 / 20260331)的财报是否存在于服务器
     */
    public static PrepareResult prepareByInput(String input) throws Exception {
        String reportDate = normalizeQuarterInput(input);
        if (reportDate == null) {
            throw new IllegalArgumentException("财报季度格式不正确: " + input
                    + "，请输入 yyyyMM(如 202603) 或 yyyyMMdd(如 20260331)");
        }
        return prepare(reportDate);
    }

    /**
     * 执行下载 + 解析 + 入库
     *
     * @param prepared  检查阶段结果
     * @param overwrite true 表示先删除本地该报告期数据再导入
     */
    public static ImportResult importReport(PrepareResult prepared, boolean overwrite) throws Exception {
        return importReport(prepared, overwrite, null);
    }

    /**
     * 执行下载 + 解析 + 入库，并在导入的是「最新一季」时联动检查股本 / 股东数据。
     *
     * @param progress 进度提示回调（可为 null），界面拿来更新「请稍候」里的文字
     */
    public static ImportResult importReport(PrepareResult prepared, boolean overwrite,
                                            java.util.function.Consumer<String> progress) throws Exception {
        if (prepared == null || prepared.reportDate == null || prepared.reportDate.trim().isEmpty()) {
            throw new IllegalArgumentException("财报报告期为空，请先检查财报数据");
        }

        FinanceFetchConfig config = FinanceFetchConfig.getInstance();
        FinanceDBManager db = new FinanceDBManager(config.dbName());
        String reportDate = prepared.reportDate.trim();
        ImportResult result = null;

        try {
            db.init();

            FinancePythonRunner.Result fetch = FinancePythonRunner.runFetch(reportDate);
            if (!fetch.isSuccess()) {
                db.failImport(reportDate, "python 执行失败, exitCode=" + fetch.getExitCode());
                throw new IllegalStateException(buildErrorMessage(
                        "下载/解析财报数据失败", reportDate, fetch.getAvailableDates()));
            }

            String fetchedDate = trim(fetch.getReportDate());
            if (!fetchedDate.isEmpty()) {
                reportDate = fetchedDate;
            }

            Path metaPath = toAbsolute(config.metaFile());
            Map<String, String> meta = readMeta(metaPath);
            List<String> columns = splitList(meta.get("COLUMNS"));
            List<String> types = splitList(meta.get("COLUMN_TYPES"));
            if (columns.isEmpty()) {
                throw new IllegalStateException("meta 文件中没有列信息: " + metaPath);
            }
            if (types.size() != columns.size()) {
                types = defaultTypes(columns);
            }

            db.ensureColumns(columns, types);

            if (overwrite) {
                db.deleteByReportDate(reportDate);
            }

            String sourceFile = meta.get("SOURCE_FILE");
            if (sourceFile == null || sourceFile.trim().isEmpty()) {
                sourceFile = trim(fetch.getSourceFile());
            }
            long fileSize = parseLong(meta.get("FILE_SIZE"));
            if (fileSize <= 0) {
                fileSize = fetch.getFileSize();
            }

            db.startImport(reportDate, sourceFile, fileSize);
            Path csvPath = resolveCsvPath(meta, fetch, config);
            int rows = db.importCsv(csvPath, columns, config.codePrefix());
            db.finishImport(reportDate, rows,
                    "CSV=" + csvPath.getFileName() + ", columns=" + columns.size());

            result = new ImportResult();
            result.reportDate = reportDate;
            result.sourceFile = sourceFile;
            result.csvFile = csvPath.toString();
            result.fileSize = fileSize;
            result.rows = rows;
            result.columns = columns.size();
            result.overwritten = overwrite;

            System.out.println("============================================================");
            System.out.println("财报数据处理完成: 报告期=" + reportDate
                    + ", 记录数=" + rows + ", 字段数=" + columns.size()
                    + (overwrite ? ", 已覆盖旧数据" : ""));
            System.out.println("数据来源: " + sourceFile + " (" + fileSize + " bytes)");
            System.out.println("数据库文件: " + db.getDbPath());
            System.out.println("============================================================");

        } catch (Exception e) {
            try {
                db.failImport(reportDate,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignore) {
                // 日志写入失败不影响主异常抛出
            }
            throw e;
        } finally {
            db.close();
        }

        // ===== 判定这次导入是不是「库里最新的一期」=====
        // 放在 finally 之后：这几步要读同一个库文件(finance.db)，财报连接没关会撞锁。
        // 股东 / 板块刷新、股本联动都用这一个判据，不各判一套。
        String newest = "";
        try {
            newest = ShareCapitalSyncService.latestFinanceReportDate();
        } catch (Exception e) {
            System.err.println("读取财报最新报告期失败: " + e.getMessage());
        }
        result.latestReportDate = newest;
        result.latestReport = isLatestReportInDb(result.reportDate, newest);

        // ===== 最后一季：顺手把股本数据补上 =====
        // 财报已经落库，此时失败也不影响主结果。
        syncCapitalIfLatest(result, progress);
        return result;
    }

    /**
     * 只补指定几只股票在某报告期的财报，同一季的其它股票一行都不动。
     *
     * <p>为什么需要它：查缺补漏经常只查出「缺 1~2 只」，而 {@link #importReport} 是整季重导
     * （先 {@code deleteByReportDate} 删 5000+ 行再整批写回），为一只股票付出整季的代价太贵，
     * 万一中途失败还会把本来好的数据留在半截状态。这里改成按 {@code (code, report_date)}
     * 主键 {@code INSERT OR REPLACE} 单条写入。</p>
     *
     * <p>数据来源仍是整季 CSV：本地已有同一报告期的 CSV（script 输出的 {@code tdx_finance.csv}
     * 与 meta）时直接复用、零下载；没有才跑一次 python 生成。复用判据是 meta 里的
     * {@code REPORT_DATE} 与目标期一致，不会拿上一季的 CSV 去补这一季。</p>
     *
     * @param reportDate 报告期 yyyyMMdd
     * @param codes      要补的股票代码，6 位或带前缀都行
     * @return 结果（{@code rows} 是实际写入的行数）
     */
    public static ImportResult importCodes(String reportDate, Collection<String> codes,
                                           java.util.function.Consumer<String> progress) throws Exception {
        String target = trim(reportDate);
        if (target.isEmpty()) {
            throw new IllegalArgumentException("财报报告期为空，无法补数据");
        }
        if (codes == null || codes.isEmpty()) {
            throw new IllegalArgumentException("没有要补的股票代码");
        }

        FinanceFetchConfig config = FinanceFetchConfig.getInstance();
        FinanceDBManager db = new FinanceDBManager(config.dbName());
        ImportResult result = new ImportResult();

        try {
            db.init();

            Path metaPath = toAbsolute(config.metaFile());
            Map<String, String> meta = readMeta(metaPath);
            Path csvPath = null;
            boolean reused = false;
            if (target.equals(trim(meta.get("REPORT_DATE")))) {
                Path candidate = resolveCsvPath(meta, null, config);
                if (candidate != null && Files.isRegularFile(candidate)) {
                    csvPath = candidate;
                    reused = true;
                }
            }
            if (csvPath == null) {
                if (progress != null) {
                    progress.accept("本地没有该报告期的财报包，正在下载解析（整季一次，只为取这几只）...");
                }
                FinancePythonRunner.Result fetch = FinancePythonRunner.runFetch(target);
                if (!fetch.isSuccess()) {
                    throw new IllegalStateException(buildErrorMessage(
                            "下载/解析财报数据失败", target, fetch.getAvailableDates()));
                }
                meta = readMeta(metaPath);
                if (!target.equals(trim(meta.get("REPORT_DATE")))) {
                    throw new IllegalStateException("拿到的财报包不是报告期 " + target
                            + "（实际 " + trim(meta.get("REPORT_DATE")) + "），已中止，未改动任何数据");
                }
                csvPath = resolveCsvPath(meta, fetch, config);
            }
            if (progress != null) {
                progress.accept((reused ? "复用本地已下载的财报包，" : "财报包已就绪，")
                        + "只写入 " + codes.size() + " 只股票...");
            }

            List<String> columns = splitList(meta.get("COLUMNS"));
            List<String> types = splitList(meta.get("COLUMN_TYPES"));
            if (columns.isEmpty()) {
                throw new IllegalStateException("meta 文件中没有列信息: " + metaPath);
            }
            if (types.size() != columns.size()) {
                types = defaultTypes(columns);
            }
            db.ensureColumns(columns, types);

            int rows = db.importCsv(csvPath, columns, config.codePrefix(),
                    new java.util.LinkedHashSet<String>(codes));

            result.reportDate = target;
            result.sourceFile = trim(meta.get("SOURCE_FILE"));
            result.csvFile = csvPath.toString();
            result.fileSize = parseLong(meta.get("FILE_SIZE"));
            result.rows = rows;
            result.columns = columns.size();
            result.overwritten = false;
            result.capitalMessage = "只补 " + codes.size() + " 只，未动股本数据";

            // 该季原本没有成功的导入记录（比如上次整季导入失败）时补一条，
            // 否则「已处理完成」的判据一直为假，下次检查还会把它当整季没导。
            if (rows > 0 && !db.isReportDateDone(target)) {
                db.finishImport(target, (int) db.countFinanceByReportDate(target),
                        "按代码补录 " + rows + " 只");
            }

            System.out.println("财报按代码补录完成: 报告期=" + target + ", 请求 " + codes.size()
                    + " 只, 实际写入 " + rows + " 行" + (reused ? "（复用本地财报包）" : ""));
            return result;
        } finally {
            db.close();
        }
    }

    /**
     * 本次导入的报告期是不是「库里最新的一期」。股东/板块刷新、股本联动共用这一个判据。
     *
     * <p>判据 = 导入写库<b>之后</b> {@code T_D_Finance} 的 {@code MAX(report_date)} 是否等于本次报告期：
     * 首次导入 / 覆盖最新一季 → true；补导入比库里更老的季度（库里有 20260930，又补 20260630）→ false。</p>
     *
     * <p>不用服务器上的「可用报告期列表」：服务器常留着下一季的空占位文件，
     * 拿它当基准会几乎永远判成「是最新」，等于回到「每季度都刷」。</p>
     *
     * @param reportDate 本次导入的报告期 yyyyMMdd
     * @param newestInDb 导入完成后库里最大的报告期；空串表示库为空或读不到表（首次导入）
     */
    public static boolean isLatestReportInDb(String reportDate, String newestInDb) {
        String current = reportDate == null ? "" : reportDate.trim();
        if (current.isEmpty()) {
            return false;
        }
        String newest = newestInDb == null ? "" : newestInDb.trim();
        if (newest.isEmpty()) {
            // 库是空的（或 T_D_Finance 还没建）：本次导入即库里唯一一期，算最新
            return true;
        }
        return current.equals(newest);
    }

    /**
     * 只有「导入的是库里最新的一期」才联动股本数据：
     * 老季度补历史的代价大且没用（历史已在库里），最新一季才真正决定明天的换手率分母。
     */
    private static void syncCapitalIfLatest(ImportResult result,
                                           java.util.function.Consumer<String> progress) {
        if (result == null || result.reportDate == null) {
            return;
        }
        if (!result.latestReport) {
            result.capitalMessage = "导入的不是最新一季，跳过股本数据检查";
            return;
        }
        result.capitalChecked = true;
        try {
            if (progress != null) {
                progress.accept("正在检查股本数据...");
            }
            ShareCapitalSyncService.SyncResult sync =
                    ShareCapitalSyncService.afterFinanceImport(result.reportDate, progress);
            result.capitalRows = sync.akshareRows;
            result.capitalMessage = sync.message;
            // 股本变了，K 线图里缓存的换手率时间线要作废
            ShareCapitalRepository.invalidate();
            System.out.println("股本数据: " + sync.message);
        } catch (Exception e) {
            result.capitalMessage = "股本数据检查失败: " + e.getMessage();
            System.err.println(result.capitalMessage);
        }
    }

    /**
     * 校验季度输入是否合法
     */
    public static boolean isValidQuarterInput(String input) {
        return normalizeQuarterInput(input) != null;
    }
    /**
     * 当前财报数据库文件绝对路径，用于界面提示
     */
    public static String dbFilePath() throws Exception {
        FinanceFetchConfig config = FinanceFetchConfig.getInstance();
        FinanceDBManager db = new FinanceDBManager(config.dbName());
        return db.getDbPath();
    }

    /**
     * 把季度输入归一成报告期 yyyyMMdd
     * 支持: 202603(2026年一季度 -> 20260331) / 202606 / 202609 / 202612 / 20260331
     */
    public static String normalizeQuarterInput(String input) {
        if (input == null) {
            return null;
        }
        String digits = input.replaceAll("[^0-9]", "");
        if (digits.length() == 6) {
            int year;
            int month;
            try {
                year = Integer.parseInt(digits.substring(0, 4));
                month = Integer.parseInt(digits.substring(4, 6));
            } catch (NumberFormatException e) {
                return null;
            }
            if (month < 1 || month > 12) {
                return null;
            }
            int quarterMonth;
            if (month <= 3) {
                quarterMonth = 3;
            } else if (month <= 6) {
                quarterMonth = 6;
            } else if (month <= 9) {
                quarterMonth = 9;
            } else {
                quarterMonth = 12;
            }
            int lastDay = YearMonth.of(year, quarterMonth).lengthOfMonth();
            return String.format("%04d%02d%02d", year, quarterMonth, lastDay);
        }
        if (digits.length() == 8) {
            try {
                int year = Integer.parseInt(digits.substring(0, 4));
                int month = Integer.parseInt(digits.substring(4, 6));
                int day = Integer.parseInt(digits.substring(6, 8));
                LocalDate.of(year, month, day);
                return digits;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 内部方法
    private static PrepareResult prepare(String reportDate) throws Exception {
        FinanceFetchConfig config = FinanceFetchConfig.getInstance();
        FinancePythonRunner.Result check = (reportDate == null)
                ? FinancePythonRunner.runCheckOnly()
                : FinancePythonRunner.runCheckOnly(reportDate);

        if (!check.isSuccess()) {
            throw new IllegalStateException(buildErrorMessage(
                    "检查财报数据失败", reportDate, check.getAvailableDates()));
        }

        PrepareResult result = new PrepareResult();
        String date = trim(check.getReportDate());
        if (date.isEmpty()) {
            date = reportDate;
        }
        if (date == null || date.isEmpty()) {
            throw new IllegalStateException("未能获取财报报告期");
        }
        result.reportDate = date;
        result.sourceFile = check.getSourceFile();
        result.fileSize = check.getFileSize();
        result.availableDates = check.getAvailableDates();

        FinanceDBManager db = new FinanceDBManager(config.dbName());
        try {
            db.init();
            result.localRowCount = db.countFinanceByReportDate(result.reportDate);
            result.localDone = db.isReportDateDone(result.reportDate);
        } finally {
            db.close();
        }
        return result;
    }

    private static String buildErrorMessage(String prefix, String reportDate, String availableDates) {
        StringBuilder message = new StringBuilder(prefix);
        if (reportDate != null && !reportDate.trim().isEmpty()) {
            message.append("（报告期 ").append(reportDate.trim()).append("）");
        }
        if (availableDates != null && !availableDates.trim().isEmpty()) {
            message.append("\n服务器可用报告期: ").append(formatDates(availableDates));
        }
        return message.toString();
    }

    private static String formatDates(String dates) {
        List<String> list = splitList(dates);
        if (list.size() <= 12) {
            return String.join(",", list);
        }
        return String.join(",", list.subList(0, 12)) + " ...";
    }

    private static Path resolveCsvPath(Map<String, String> meta,
                                       FinancePythonRunner.Result fetch,
                                       FinanceFetchConfig config) {
        String csv = meta.get("CSV_FILE");
        if (csv == null || csv.trim().isEmpty()) {
            csv = fetch == null ? null : fetch.getCsvFile();
        }
        if (csv == null || csv.trim().isEmpty()) {
            csv = config.csvFile();
        }
        return toAbsolute(csv);
    }

    private static Map<String, String> readMeta(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) {
            return map;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int index = text.indexOf('=');
            if (index <= 0) {
                continue;
            }
            map.put(text.substring(0, index).trim(), text.substring(index + 1).trim());
        }
        return map;
    }

    private static List<String> splitList(String value) {
        List<String> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                list.add(item);
            }
        }
        return list;
    }

    private static List<String> defaultTypes(List<String> columns) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            types.add("TEXT");
        }
        return types;
    }

    private static Path toAbsolute(String path) {
        Path target = Paths.get(path);
        if (!target.isAbsolute()) {
            target = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return target.toAbsolutePath().normalize();
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}