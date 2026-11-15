package updater.fetchdata.tdx.xdxr;

import updater.cfg.AppConfigManager;
import updater.cfg.ConfigException;
import updater.cfg.EnhancedConfigReader;
import updater.fetchdata.DividendData;
import updater.fetchdata.tdx.TdxDataDir;
import updater.log.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通过 Python 脚本(python/read_tdx_gbbq.py，内部使用 pytdx 的 GbbqReader)
 * 读取本地数据来源的 gbbq 股本变迁文件，得到除权除息数据并转换为 {@link DividendData}。
 *
 * 数据流：
 *   本地数据来源 T0002/hq_cache/gbbq -> python(pytdx GbbqReader) -> csv -> 本类解析 -> DividendData
 */
public class TdxLocalXdxrReader {

    /** config.properties 中的配置键 */
    public static final String KEY_PYTHON_FILE = "tdx.dividend.pythonFile";
    public static final String KEY_OUTPUT_FILE = "tdx.dividend.outputFile";
    /** config.properties 中的配置键；解析与探测统一走 {@link updater.cfg.PythonInterpreter} */
    public static final String KEY_PYTHON_EXE = updater.cfg.PythonInterpreter.KEY;
    public static final String KEY_FILTER_CODES = "tdx.dividend.filterCodes";
    public static final String KEY_DEBUG = "tdx.dividend.debug";

    private static final String DEFAULT_PYTHON_FILE = "python/read_tdx_gbbq.py";
    private static final String DEFAULT_OUTPUT_FILE = "data/tdx/tdx_xdxr.csv";

    /** 行情软件放除权文件的几个位置（相对本地数据来源目录），和 python 脚本里那份保持一致 */
    private static final String[] GBBQ_CANDIDATES = {
            "T0002/hq_cache/gbbq",
            "T0002/special/gbbq",
            "T0002/hq_cache/gbbq.dat",
            "T0002/special/gbbq.dat",
    };

    /** 生成的股票代码列表文件(给 python 校正市场前缀 / 可选过滤) */
    private static final String CODES_FILE = "data/tdx/tdx_xdxr_codes.txt";

    /** 只有类别 1(除权除息)参与复权 */
    private static final int CATEGORY_XDXR = 1;

    private static final int MIN_DATE = 19000101;
    private static final int MAX_DATE = 21001231;

    /** 逐条单位判断：每10股派现 > 100 元 / 每股配股价 > 1000 元，视为以"厘"存储 */
    private static final double FENHONG_CENT_LIMIT = 100.0;
    private static final double PEIGUJIA_CENT_LIMIT = 1000.0;
    private static final double UNIT_CENT = 1000.0;
    /** 换算后依然离谱的，视为脏数据丢弃 */
    private static final double FENHONG_INVALID_LIMIT = 1000.0;
    private static final double PEIGUJIA_INVALID_LIMIT = 10000.0;

    private TdxLocalXdxrReader() {
    }

    /**
     * csv 中的一行记录
     */
    public static class XdxrRow {
        /** 带市场前缀，如 sh600000 */
        public String code;
        /** 除权除息日 yyyyMMdd */
        public int date;
        /** 类别，1=除权除息 */
        public int category;
        /** 每10股派现(元) */
        public double fenhong;
        /** 每股配股价(元) */
        public double peigujia;
        /** 每10股送转股 */
        public double songgu;
        /** 每10股配股 */
        public double peigu;
    }

    /**
     * 读取本地数据来源的除权数据，返回 股票代码(带前缀) -> 除权记录 的映射。
     */
    public static Map<String, List<DividendData>> readFromLocalTdx()
            throws ConfigException, IOException, InterruptedException {

        EnhancedConfigReader cfg = AppConfigManager.getInstance().getConfigReader();

        String tdxDir = TdxDataDir.require().getAbsolutePath();
        // 脚本与中间文件都不赌当前目录：jar 被挪到别处时按「工作目录 → jar 目录 → jar 上级 →
        // jar 内自带的那份」找（见 ScriptPaths），否则除权任务会因为 python 脚本找不到直接失败
        File scriptFile = updater.cfg.ScriptPaths.resolve(
                getStringOrDefault(cfg, KEY_PYTHON_FILE, DEFAULT_PYTHON_FILE));
        File outputCsv = updater.cfg.ScriptPaths.workFile(
                getStringOrDefault(cfg, KEY_OUTPUT_FILE, DEFAULT_OUTPUT_FILE));
        // 解释器：配置值能用就用，否则自动探测 python3 / python / py（见 PythonInterpreter）
        String pythonExe = updater.cfg.PythonInterpreter.resolve();
        if (pythonExe == null) {
            throw new ConfigException("没有找到可用的 python 解释器，除权任务需要它解析本地 gbbq 文件。"
                    + "请安装 python 3（需要 pytdx、pandas），或在更新画面「设置 → python 解释器」里指定路径");
        }
        if (scriptFile == null || !scriptFile.isFile()) {
            throw new IOException("除权脚本不存在: "
                    + (scriptFile == null ? "（未配置）" : scriptFile.getAbsolutePath())
                    + "\n请检查 config.properties 里的 " + KEY_PYTHON_FILE
                    + "，或把 python 脚本放回程序目录");
        }
        // gbbq 是行情客户端下发的数据文件，不随任何公开地址单独下载：先在本地找，
        // 找不到就当场把「怎么补」说清楚，别等 python 报一句「未找到 gbbq 文件」再让人猜
        File gbbq = findGbbq(new File(tdxDir.trim()));
        if (gbbq == null) {
            throw new ConfigException(gbbqMissingMessage(new File(tdxDir.trim())));
        }
        Log.info("gbbq 文件: " + gbbq.getAbsolutePath());

        boolean filterCodes = getBooleanOrDefault(cfg, KEY_FILTER_CODES, false);
        boolean debug = getBooleanOrDefault(cfg, KEY_DEBUG, true);

        // 1. 本地 db 股票代码（用于校正市场前缀，可选用于过滤）
        List<String> dbCodes = listDbCodes(cfg);
        String codesFile = prepareCodesFile(dbCodes);
        Log.info("本地db股票数: " + dbCodes.size() + ", filterCodes=" + filterCodes);

        // 2. 调用 python(pytdx) 解析 gbbq
        int exitCode = runPython(pythonExe, scriptFile.getAbsolutePath(), tdxDir.trim(),
                outputCsv.getAbsolutePath(), codesFile, filterCodes, debug);
        if (exitCode != 0) {
            throw new IOException("python 脚本执行失败, exitCode=" + exitCode
                    + ", script=" + scriptFile.getAbsolutePath()
                    + "（请手动执行一次该脚本查看详细报错）");
        }

        // 3. 解析 csv
        List<XdxrRow> rows = readCsv(outputCsv);
        Log.info("本地除权记录数: " + rows.size() + " (csv=" + outputCsv.getAbsolutePath() + ")");
        if (rows.isEmpty()) {
            throw new IOException("除权 csv 中没有数据行(可能只有表头): " + outputCsv.getAbsolutePath());
        }

        // 4. 转成 DividendData
        return toDividendData(rows);
    }

    /**
     * 解析 python 导出的 csv（首行可为表头）。
     */
    public static List<XdxrRow> readCsv(File csv) throws IOException {
        List<XdxrRow> rows = new ArrayList<XdxrRow>();
        if (csv == null || !csv.isFile()) {
            Log.error("除权 csv 文件不存在: " + csv);
            return rows;
        }
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        boolean firstLine = true;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String text = line.replace("\uFEFF", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            if (firstLine) {
                firstLine = false;
                if (text.toLowerCase(Locale.ROOT).startsWith("code")) {
                    continue;
                }
            }
            String[] parts = text.split(",");
            if (parts.length < 7) {
                continue;
            }
            XdxrRow row = new XdxrRow();
            try {
                row.code = parts[0].trim();
                row.date = Integer.parseInt(parts[1].trim());
                row.category = Integer.parseInt(parts[2].trim());
                row.fenhong = Double.parseDouble(parts[3].trim());
                row.peigujia = Double.parseDouble(parts[4].trim());
                row.songgu = Double.parseDouble(parts[5].trim());
                row.peigu = Double.parseDouble(parts[6].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (row.code.isEmpty()) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 把 csv 记录转换为项目内部的 DividendData（按股票分组）。
     *
     * 单位约定：
     *  DividendData.cashDividend   = 每股现金分红(元)
     *  DividendData.stockGiftCount = 每10股送转股
     *  DividendData.allotment      = 每10股配股
     *  DividendData.allotmentPrice = 每股配股价(元)
     */
    public static Map<String, List<DividendData>> toDividendData(List<XdxrRow> rows) {
        Map<String, List<DividendData>> result = new LinkedHashMap<String, List<DividendData>>();
        if (rows == null || rows.isEmpty()) {
            return result;
        }

        int unitFixed = 0;
        int invalidSkipped = 0;
        int zeroSkipped = 0;

        Map<String, List<DividendData>> grouped = new LinkedHashMap<String, List<DividendData>>();
        for (XdxrRow row : rows) {
            if (row.category != CATEGORY_XDXR) {
                continue;
            }
            if (row.date < MIN_DATE || row.date > MAX_DATE) {
                continue;
            }
            if (isZero(row.fenhong) && isZero(row.songgu) && isZero(row.peigu)) {
                zeroSkipped++;
                continue;
            }

            // 逐条单位兜底（python 端已归一化，此处防御未归一化的数据）
            double fenhong = normalizePrice(row.fenhong, FENHONG_CENT_LIMIT);
            double peigujia = normalizePrice(row.peigujia, PEIGUJIA_CENT_LIMIT);
            if ((!isZero(fenhong) && fenhong != row.fenhong)
                    || (!isZero(peigujia) && peigujia != row.peigujia)) {
                unitFixed++;
            }
            if (Math.abs(fenhong) > FENHONG_INVALID_LIMIT
                    || Math.abs(peigujia) > PEIGUJIA_INVALID_LIMIT) {
                invalidSkipped++;
                continue;
            }

            DividendData dividend = new DividendData();
            dividend.stockCode = row.code;
            dividend.exDividendDate = row.date;
            dividend.cashDividend = (float) (fenhong / 10.0);   // 每股派现
            dividend.stockGiftCount = (float) row.songgu;       // 每10股送转
            dividend.allotment = (float) row.peigu;             // 每10股配股
            dividend.allotmentPrice = (float) peigujia;         // 每股配股价
            dividend.text = buildText(fenhong, peigujia, row.songgu, row.peigu);

            List<DividendData> list = grouped.get(row.code);
            if (list == null) {
                list = new ArrayList<DividendData>();
                grouped.put(row.code, list);
            }
            list.add(dividend);
        }

        Log.info("除权数据转换 股票数:" + grouped.size()
                + " 单位换算条数:" + unitFixed
                + " 全0跳过:" + zeroSkipped
                + " 脏数据跳过:" + invalidSkipped);

        result.putAll(grouped);
        return result;
    }

    /**
     * 逐条价格单位换算：仅当单条记录数值明显超出合理范围时才认为以"厘"存储
     */
    private static double normalizePrice(double value, double centLimit) {
        if (Math.abs(value) > centLimit) {
            return value / UNIT_CENT;
        }
        return value;
    }

    /**
     * 生成除权内容文本（写入 T_Divdend.TEXT，长度需 &lt; 64）
     */
    private static String buildText(double fenhong, double peigujia,
                                    double songgu, double peigu) {
        StringBuilder sb = new StringBuilder();
        if (songgu > 0) {
            sb.append(String.format(Locale.ROOT, "10送%.3f股", songgu));
        }
        if (peigu > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "10配%.3f股 配股价%.3f元", peigu, peigujia));
        }
        if (fenhong > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "10派%.3f元", fenhong));
        }
        if (sb.length() == 0) {
            sb.append("除权除息");
        }
        String text = sb.toString();
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    /**
     * 收集本地数据库中的股票代码(带前缀)，如 sh600000
     */
    private static List<String> listDbCodes(EnhancedConfigReader cfg) throws ConfigException {
        List<String> codes = new ArrayList<String>();
        String dbPath = updater.mount.MountConfig.currentDbDir();
        if (dbPath.isEmpty()) {
            return codes;
        }
        File dir = new File(dbPath);
        File[] files = dir.listFiles();
        if (files == null) {
            Log.error("db 目录不存在或不可读: " + dir.getAbsolutePath());
            return codes;
        }
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".db")) {
                codes.add(name.substring(0, name.length() - 3));
            }
        }
        return codes;
    }

    /**
     * 把股票代码写入临时文件，供 python 校正市场前缀（或过滤）
     */
    private static String prepareCodesFile(List<String> dbCodes) throws IOException {
        if (dbCodes == null || dbCodes.isEmpty()) {
            return null;
        }
        File file = updater.cfg.ScriptPaths.workFile(CODES_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        StringBuilder sb = new StringBuilder();
        for (String code : dbCodes) {
            sb.append(code).append('\n');
        }
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return file.getPath();
    }

    /**
     * 执行 python 脚本，把 python 输出同步打印到控制台
     */
    private static int runPython(String pythonExe, String scriptPath, String tdxDir,
                                 String outputFile, String codesFile,
                                 boolean filterCodes, boolean debug)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<String>();
        // 解释器可能是带空格的绝对路径（"C:\Program Files\Python\python.exe"），按空格拆开拼
        command.addAll(updater.cfg.PythonInterpreter.split(pythonExe));
        command.add(scriptPath);
        command.add("--tdx-dir");
        command.add(tdxDir);
        command.add("--output-file");
        command.add(outputFile);
        if (codesFile != null) {
            command.add("--codes-file");
            command.add(codesFile);
        }
        if (filterCodes) {
            command.add("--filter-codes");
        }
        if (debug) {
            command.add("--debug");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(updater.cfg.ProgramHome.dir());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUNBUFFERED", "1");

        Log.info("执行命令: " + String.join(" ", command));

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                System.out.println("[python] " + line);
            }
        } finally {
            reader.close();
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Log.error("python 输出:\n" + output);
        }
        return exitCode;
    }

    /**
     * 在本地数据来源目录里找 gbbq。
     *
     * <p>常见布局是 {@code T0002/hq_cache/gbbq}；有的版本在 {@code T0002/special}，
     * 也有带 {@code .dat} 后缀的。都不中就在 T0002 下再捞两层（跳过 vipdoc —— 那里是
     * 几万只股票的日线文件，全扫一遍要好几秒且不可能有 gbbq）。</p>
     */
    private static File findGbbq(File tdxDir) {
        if (tdxDir == null || !tdxDir.isDirectory()) {
            return null;
        }
        for (String rel : GBBQ_CANDIDATES) {
            File file = new File(tdxDir, rel.replace('/', File.separatorChar));
            if (file.isFile()) {
                return file;
            }
        }
        File hit = scanGbbq(new File(tdxDir, "T0002"), 2);
        return hit != null ? hit : scanGbbq(tdxDir, 2);
    }

    /** 递归找 gbbq / gbbq.*，depth 是还能往下钻的层数 */
    private static File scanGbbq(File dir, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if ("gbbq".equals(name) || name.startsWith("gbbq.")) {
                    return file;
                }
            }
        }
        for (File file : files) {
            if (file.isDirectory() && !"vipdoc".equalsIgnoreCase(file.getName())) {
                File hit = scanGbbq(file, depth - 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 找不到 gbbq 时给的话：这个文件从哪来、目录该指哪层、有没有别的路 */
    private static String gbbqMissingMessage(File tdxDir) {
        return "在本地数据来源目录里找不到行情软件下发的除权文件 gbbq。\n"
                + "目录: " + tdxDir.getAbsolutePath() + "\n"
                + "找过: T0002\\hq_cache\\gbbq、T0002\\special\\gbbq（含 .dat 写法）\n\n"
                + "这个文件由行情客户端下发，没有单独的下载地址，补的办法：\n"
                + "  1) 打开你平时用的行情软件并登录；\n"
                + "  2) 菜单「系统 → 盘后数据下载」（或按 Ctrl+F1）；\n"
                + "  3) 勾选「除权除息数据」，时间范围选「所有」，点「开始下载」；\n"
                + "  4) 下完就在 <行情软件安装目录>\\T0002\\hq_cache\\gbbq，不用手动搬。\n\n"
                + "本地数据来源要指到「行情软件安装目录」这一层（里面有 vipdoc 和 T0002），\n"
                + "不能指到 vipdoc 里面，可以点右上「路径设置 → 本地数据来源」重新选。\n\n"
                + "不想装这类行情软件：把「除权数据更新」右侧的来源换成「东方财富」，\n"
                + "那条线路在线逐只取，不需要 gbbq。";
    }

    private static String getStringOrDefault(EnhancedConfigReader cfg, String key, String def) {
        String value = null;
        try {
            if (cfg.containsKey(key)) {
                value = cfg.getString(key);
            }
        } catch (Exception ignore) {
            value = null;
        }
        if (value == null || value.trim().isEmpty()) {
            return def;
        }
        return value.trim();
    }

    private static boolean getBooleanOrDefault(EnhancedConfigReader cfg, String key, boolean def) {
        try {
            if (cfg.containsKey(key)) {
                return "true".equalsIgnoreCase(cfg.getString(key).trim());
            }
        } catch (Exception ignore) {
            // ignore
        }
        return def;
    }

    private static boolean isZero(double v) {
        return Math.abs(v) < 1e-10;
    }
}
