package updater.fetchdata.capital;

import updater.db.DBException;
import updater.db.ShareCapital;
import updater.db.ShareCapitalDBManager;
import updater.finance.FinanceFields;
import updater.finance.FinanceRecord;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 股本 / 股东数据的同步服务：把「本地财报字段」和「akshare 抓回来的数据」
 * 都写进 T_D_ShareCapital，供 K 线图算换手率。
 * <p>
 * 两条数据来源的分工：
 * <ul>
 *   <li><b>本地财报</b>（source=FIN）：gpcw 里本来就有总股本、已上市流通A股、
 *       股东人数、十大流通股东合计持股，每个报告期一次，离线可用、永远是最新一期；</li>
 *   <li><b>akshare</b>（source=CNINFO/EM）：巨潮的股本变动明细能补到财报之前的历史
 *       （增发、解禁、送转这些时点），东财的十大流通股东补上「大股东持股」。</li>
 * </ul>
 * 两者按 (code, 日期) 合并，同一天两边都有值时以先写入的为准、缺的字段互相补齐。
 */
public final class ShareCapitalSyncService {

    public static final String SOURCE_FIN = "FIN";
    public static final String SOURCE_CNINFO = "CNINFO";
    public static final String SOURCE_EM = "EM";

    private ShareCapitalSyncService() {
    }

    /**
     * 同步结果，给界面提示用
     */
    public static class SyncResult {
        /** 从本地财报回填的行数 */
        public int financeRows;
        /** 从 akshare 写入的行数 */
        public int akshareRows;
        /** akshare 覆盖的股票数 */
        public int akshareCodes;
        /** 获取失败的请求数 */
        public int errors;
        /** 是否真的跑了 akshare（已齐全时跳过） */
        public boolean akshareRan;
        /** 给用户看的一句话说明 */
        public String message = "";

        @Override
        public String toString() {
            return message;
        }
    }

    // ==================== 1. 本地财报回填 ====================

    /**
     * 从财报字段回填股本数据。
     * <p>
     * 只用本地库，不发请求，几百毫秒就能跑完一个报告期，
     * 所以「更新财报」之后立刻就能拿到这一期的流通股。
     *
     * @param reportDate 报告期 yyyyMMdd；null / 空 表示回填全部报告期
     * @return 写入的行数
     */
    public static int backfillFromFinance(String reportDate) throws Exception {
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            db.ensureTable();
            String where = (reportDate == null || reportDate.trim().isEmpty())
                    ? "" : " WHERE report_date = ?";
            String sql = "SELECT code, report_date, \"fields\" FROM T_D_Finance" + where;
            List<ShareCapital> rows = new ArrayList<>();
            try (PreparedStatement pstmt = db.getConnection().prepareStatement(sql)) {
                if (!where.isEmpty()) {
                    pstmt.setString(1, reportDate.trim());
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        ShareCapital item = toShareCapital(rs.getString(1), rs.getString(2), rs.getString(3));
                        if (item != null) {
                            rows.add(item);
                        }
                    }
                }
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                    return 0;
                }
                throw e;
            }
            if (rows.isEmpty()) {
                return 0;
            }
            int written = db.upsertAll(rows);
            System.out.println("股本数据回填(财报字段): 报告期=" + (reportDate == null ? "全部" : reportDate)
                    + ", 行数=" + written);
            return written;
        } finally {
            db.close();
        }
    }

    /**
     * 一行财报 → 一条股本数据；股本与股东字段全空时返回 null
     */
    private static ShareCapital toShareCapital(String code, String reportDate, String fields) {
        FinanceRecord record = FinanceRecord.parse(code, reportDate, fields);
        double total = record.value(FinanceFields.TOTAL_SHARES);
        double floatA = record.value(FinanceFields.FLOAT_A_SHARES);
        if (!isPositive(total) && !isPositive(floatA)) {
            return null;
        }
        ShareCapital item = new ShareCapital(code, reportDate);
        item.totalShares = isPositive(total) ? total : null;
        item.floatShares = isPositive(floatA) ? floatA : null;
        // 流通A股之外的部分（限售股 + B/H 股）只做参考，等于总股本减流通A股
        if (isPositive(total) && isPositive(floatA) && total > floatA) {
            item.restrictedShares = total - floatA;
        }
        double holderCount = record.value(FinanceFields.HOLDER_COUNT);
        if (isPositive(holderCount)) {
            item.holderCount = holderCount;
        }
        double top1 = record.value(FinanceFields.TOP1_HOLDER_SHARES);
        if (isPositive(top1)) {
            item.top1HolderShares = top1;
        }
        double top10 = record.value(FinanceFields.TOP10_FREE_HOLDER_SHARES);
        if (isPositive(top10) && top10 <= (isPositive(floatA) ? floatA : top10)) {
            item.top10HolderShares = top10;
            if (isPositive(floatA)) {
                item.top10HolderRatio = top10 / floatA * 100.0;
            }
        }
        item.source = SOURCE_FIN;
        return item;
    }

    private static boolean isPositive(double value) {
        return !Double.isNaN(value) && value > 0;
    }

    // ==================== 2. 缺失检测 ====================

    /**
     * 该报告期的股本数据是不是缺（需要去 akshare 补）
     * <p>
     * 判据：财报这一期有 N 只股票，股本表这一期只有 M 只带流通股的；
     * M 明显少于 N（低于 95%）就认为缺。没有财报数据时按「股本表里有没有这一期」判断。
     */
    public static boolean needsUpdate(String reportDate) throws Exception {
        if (reportDate == null || reportDate.trim().isEmpty()) {
            return true;
        }
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            db.ensureTable();
            long shareCount = db.countShareByDate(reportDate);
            long financeCount = countFinance(db, reportDate);
            if (financeCount <= 0) {
                return shareCount == 0;
            }
            return shareCount < financeCount * 0.95;
        } finally {
            db.close();
        }
    }

    private static long countFinance(ShareCapitalDBManager db, String reportDate) throws DBException {
        String sql = "SELECT COUNT(*) FROM T_D_Finance WHERE report_date = ?";
        try (PreparedStatement pstmt = db.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, reportDate);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return 0L;
            }
            throw new DBException("统计财报记录失败: " + reportDate, e);
        }
    }

    /**
     * 找出该报告期「财报有、股本表里没有流通股」的股票，最多 limit 只。
     * 自动更新时用它决定抓哪些，不必全市场重抓一遍。
     */
    public static List<String> missingCodes(String reportDate, int limit) throws Exception {
        List<String> codes = new ArrayList<>();
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            db.ensureTable();
            String sql = "SELECT f.code FROM T_D_Finance f"
                    + " LEFT JOIN " + ShareCapitalDBManager.TABLE + " s"
                    + " ON s.code = f.code AND s.report_date = f.report_date"
                    + " AND s.float_a_shares IS NOT NULL"
                    + " WHERE f.report_date = ? AND s.code IS NULL"
                    + " ORDER BY f.code LIMIT ?";
            try (PreparedStatement pstmt = db.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, reportDate);
                pstmt.setInt(2, Math.max(1, limit));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        codes.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().contains("no such table")) {
                throw e;
            }
        } finally {
            db.close();
        }
        return codes;
    }

    // ==================== 3. akshare 获取入库 ====================

    /**
     * 用 akshare 抓指定股票并入库
     *
     * @param codes      股票代码（sh600000 或 600000 都行）
     * @param reportDate 股东数据报告期
     * @param progress   进度回调，可为 null
     */
    public static SyncResult syncFromAkshare(List<String> codes, String reportDate,
                                             Consumer<String> progress) throws Exception {
        SyncResult result = new SyncResult();
        if (codes == null || codes.isEmpty()) {
            result.message = "没有需要获取的股票";
            return result;
        }
        AkshareCapitalConfig config = AkshareCapitalConfig.getInstance();
        File codesFile = AkshareCapitalConfig.resolve(config.codesFile());
        writeCodes(codesFile, codes);

        AkshareCapitalRunner.Result run = AkshareCapitalRunner.fetch(
                codesFile, reportDate, config.threads(), false, progress);
        result.akshareRan = true;
        result.errors = run.getErrorCount();
        if (!run.isSuccess()) {
            result.message = run.isMissingModule()
                    ? "akshare 未安装，无法获取股本数据（请在该虚拟环境执行 pip install akshare）"
                    : "akshare 抓数失败, exitCode=" + run.getExitCode();
            return result;
        }
        String outFile = run.getOutFile();
        if (outFile == null || outFile.isEmpty()) {
            outFile = AkshareCapitalConfig.resolve(config.outFile()).getAbsolutePath();
        }
        List<ShareCapital> rows = readCsv(new File(outFile));
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            db.ensureTable();
            result.akshareRows = db.upsertAll(rows);
            result.akshareCodes = run.getCodeCount() > 0 ? run.getCodeCount() : countCodes(rows);
        } finally {
            db.close();
        }
        result.message = String.format(Locale.ROOT,
                "akshare 获取 %d 只 / %d 行，失败 %d 次",
                result.akshareCodes, result.akshareRows, result.errors);
        return result;
    }

    private static int countCodes(List<ShareCapital> rows) {
        List<String> codes = new ArrayList<>();
        for (ShareCapital row : rows) {
            if (!codes.contains(row.code)) {
                codes.add(row.code);
            }
        }
        return codes.size();
    }

    private static void writeCodes(File file, List<String> codes) throws IOException {
        Path path = file.toPath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            sb.append(code.trim()).append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 akshare 脚本产出的 CSV。字段里有中文逗号与半角逗号（如变动原因
     * 「可转债转股,定期报告」），所以必须按引号规则切，不能直接 split(",")。
     */
    static List<ShareCapital> readCsv(File file) throws IOException {
        List<ShareCapital> rows = new ArrayList<>();
        if (file == null || !file.isFile()) {
            return rows;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return rows;
        }
        List<String> header = splitCsvLine(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                continue;
            }
            List<String> cells = splitCsvLine(line);
            ShareCapital item = new ShareCapital();
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                applyCell(item, header.get(c).trim(), cells.get(c));
            }
            if (item.code != null && item.reportDate != null
                    && (item.hasShareData() || item.top10HolderShares != null)) {
                rows.add(item);
            }
        }
        return rows;
    }

    private static void applyCell(ShareCapital item, String column, String value) {
        String text = value == null ? "" : value.trim();
        switch (column) {
            case "code" -> item.code = text.isEmpty() ? null : text;
            case "report_date" -> item.reportDate = text.isEmpty() ? null : text;
            case "total_shares" -> item.totalShares = number(text);
            case "float_a_shares" -> item.floatShares = number(text);
            case "restricted_a_shares" -> item.restrictedShares = number(text);
            case "top10_holder_shares" -> item.top10HolderShares = number(text);
            case "top10_holder_ratio" -> item.top10HolderRatio = number(text);
            case "top1_holder_shares" -> item.top1HolderShares = number(text);
            case "holder_count" -> item.holderCount = number(text);
            case "change_reason" -> item.changeReason = text;
            case "ann_date" -> item.annDate = text;
            case "source" -> item.source = text;
            default -> {
                // 未知列忽略：脚本以后加列不影响这里
            }
        }
    }

    private static Double number(String text) {
        if (text == null || text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            return (Double.isNaN(value) || value <= 0) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按 RFC4180 的引号规则切一行 CSV
     */
    static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    // ==================== 5. 本地清单 ====================

    /**
     * 财报库里最新的报告期（yyyyMMdd）；没有财报时返回空串。
     * 抓股东数据时用它当报告期，避免拿到还没披露的季度。
     */
    public static String latestFinanceReportDate() throws Exception {
        ShareCapitalDBManager db = new ShareCapitalDBManager();
        try {
            String sql = "SELECT MAX(report_date) FROM T_D_Finance";
            try (java.sql.Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String value = rs.next() ? rs.getString(1) : null;
                return value == null ? "" : value;
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return "";
            }
            throw e;
        } finally {
            db.close();
        }
    }

    /**
     * 本地日线库里的全部股票代码（data/db/*.db 的文件名）。
     * 全市场抓历史股本时用它当清单——只抓本地真的有行情的股票。
     */
    public static List<String> localCodes() {
        List<String> codes = new ArrayList<>();
        try {
            String dir = updater.mount.MountConfig.currentDbDir();
            if (dir.isEmpty()) {
                return codes;
            }
            File folder = AkshareCapitalConfig.resolve(dir);
            File[] files = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".db"));
            if (files == null) {
                return codes;
            }
            for (File file : files) {
                String name = file.getName();
                codes.add(name.substring(0, name.length() - 3));
            }
            java.util.Collections.sort(codes);
        } catch (Exception e) {
            System.err.println("枚举本地股票库失败: " + e.getMessage());
        }
        return codes;
    }

    // ==================== 4. 更新财报后的联动 ====================
    /**
     * 更新财报之后的联动入口：先把这一期股本从财报字段回填，
     * 发现缺得比较多再去 akshare 补（只补缺的那些股票，避免每次全市场重抓）。
     *
     * @param reportDate 刚导入的报告期
     * @param progress   进度回调，可为 null
     */
    public static SyncResult afterFinanceImport(String reportDate,
                                                Consumer<String> progress) throws Exception {
        SyncResult result = new SyncResult();
        result.financeRows = backfillFromFinance(reportDate);
        // 不按「缺得够不够多」判断，直接把还缺的补上：
        // 一般只差几十只(次新股、北交所新代码)，几十秒的事；
        // 第一次跑时整批都缺，靠 maxCodes 兜住耗时。
        int maxCodes = AkshareCapitalConfig.getInstance().maxCodes();
        List<String> codes = missingCodes(reportDate, maxCodes);
        if (codes.isEmpty()) {
            result.message = "股本数据已齐全（本地财报回填 " + result.financeRows + " 行），无需获取";
            return result;
        }
        if (progress != null) {
            progress.accept("股本数据缺 " + codes.size() + " 只，正在用 akshare 补齐...");
        }
        SyncResult fetched = syncFromAkshare(codes, reportDate, progress);
        fetched.financeRows = result.financeRows;
        return fetched;
    }
}
