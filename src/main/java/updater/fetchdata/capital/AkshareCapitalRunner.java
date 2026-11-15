package updater.fetchdata.capital;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 调用 python 执行 akshare 股本获取脚本。
 * <p>
 * 与财报那条链（{@code FinancePythonRunner}）的约定一致：
 * 脚本往 stdout 打 {@code KEY=VALUE}，最后打一行 {@code SUCCESS:<file>}；
 * 日志与进度走 stderr，这里合并成一个流逐行读，遇到 {@code PROGRESS} 就回调给界面。
 */
public final class AkshareCapitalRunner {

    private static final String SUCCESS_PREFIX = "SUCCESS:";

    private AkshareCapitalRunner() {
    }

    /**
     * 只检测环境：akshare 装了没有、解释器是哪个
     */
    public static Result check() throws Exception {
        return execute(true, null, null, 0, false, null);
    }

    /**
     * 抓一批股票
     *
     * @param codesFile  代码清单（每行一个）
     * @param reportDate 股东数据报告期 yyyyMMdd，null 表示用脚本当天日期
     * @param threads    并发线程数
     * @param resume     跳过结果文件里已有的股票
     * @param progress   进度回调，收到的是脚本原文行（形如 PROGRESS 1/300 ...），可为 null
     */
    public static Result fetch(File codesFile, String reportDate, int threads,
                               boolean resume, Consumer<String> progress) throws Exception {
        if (codesFile == null || !codesFile.isFile()) {
            throw new IllegalArgumentException("股票代码清单不存在: "
                    + (codesFile == null ? "null" : codesFile.getAbsolutePath()));
        }
        return execute(false, codesFile, reportDate, threads, resume, progress);
    }

    private static Result execute(boolean checkOnly, File codesFile, String reportDate, int threads,
                                  boolean resume, Consumer<String> progress) throws Exception {
        AkshareCapitalConfig config = AkshareCapitalConfig.getInstance();
        String workDir = updater.cfg.ProgramHome.path();

        // ===== 1. 解释器 =====
        String pythonCommand = config.pythonExe();
        File exeFile = AkshareCapitalConfig.resolve(pythonCommand);
        if (pythonCommand.contains("/") || pythonCommand.contains("\\")) {
            if (!exeFile.isFile()) {
                throw new IllegalStateException("python 解释器不存在: " + exeFile.getAbsolutePath()
                        + "，请检查 config.properties 的 akshare.pythonExe / tdx.dividend.pythonExe");
            }
            if (!exeFile.canExecute()) {
                throw new IllegalStateException("python 解释器没有执行权限: " + exeFile.getAbsolutePath()
                        + "，请执行: chmod +x " + exeFile.getAbsolutePath());
            }
            pythonCommand = exeFile.getAbsolutePath();
        }

        // ===== 2. 脚本 =====
        File scriptFile = AkshareCapitalConfig.resolve(config.pythonFile());
        if (!scriptFile.isFile()) {
            throw new IllegalStateException("akshare 抓数脚本不存在: " + scriptFile.getAbsolutePath());
        }
        File outFile = AkshareCapitalConfig.resolve(config.outFile());

        // ===== 3. 组装命令 =====
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.add("-u");
        command.add(scriptFile.getAbsolutePath());
        command.add("--out");
        command.add(outFile.getAbsolutePath());
        if (checkOnly) {
            command.add("--check");
        } else {
            command.add("--codes-file");
            command.add(codesFile.getAbsolutePath());
            command.add("--threads");
            command.add(String.valueOf(Math.max(1, threads)));
            if (reportDate != null && !reportDate.trim().isEmpty()) {
                command.add("--report-date");
                command.add(reportDate.trim());
            }
            if (resume) {
                command.add("--resume");
            }
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(workDir));
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUNBUFFERED", "1");

        System.out.println("执行命令: " + String.join(" ", command));
        Process process = builder.start();

        Result result = new Result();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                System.out.println("[akshare] " + line);
                result.parseLine(line);
                if (progress != null && line.startsWith("PROGRESS")) {
                    progress.accept(line);
                }
            }
        }
        int exitCode = process.waitFor();
        result.exitCode = exitCode;
        result.output = output.toString();
        result.pythonCommand = pythonCommand;
        if (exitCode != 0) {
            System.err.println("akshare 抓数失败, exitCode=" + exitCode + ", 解释器=" + pythonCommand);
        }
        return result;
    }

    /**
     * 脚本执行结果
     */
    public static class Result {

        private int exitCode = -1;
        private String output = "";
        private String pythonCommand = "";
        private String successPath;
        private final Map<String, String> values = new LinkedHashMap<>();

        private void parseLine(String line) {
            if (line == null) {
                return;
            }
            String text = line.trim();
            if (text.startsWith(SUCCESS_PREFIX)) {
                successPath = text.substring(SUCCESS_PREFIX.length()).trim();
                return;
            }
            int index = text.indexOf('=');
            if (index <= 0) {
                return;
            }
            String key = text.substring(0, index).trim();
            if (!key.matches("[A-Z][A-Z0-9_]*")) {
                return;
            }
            values.put(key, text.substring(index + 1).trim());
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public String get(String key) {
            return values.get(key);
        }

        public String getPythonExe() {
            String value = values.get("PYTHON_EXE");
            return value == null || value.isEmpty() ? pythonCommand : value;
        }

        public String getAkshareVersion() {
            return values.get("AKSHARE_VERSION");
        }

        public String getOutFile() {
            String value = values.get("OUT_FILE");
            if (value == null || value.isEmpty()) {
                return successPath;
            }
            return value;
        }

        public int getRowCount() {
            return intValue("ROW_COUNT");
        }

        public int getCodeCount() {
            return intValue("CODE_COUNT");
        }

        public int getErrorCount() {
            return intValue("ERROR_COUNT");
        }

        /**
         * akshare 没装时脚本会以 exitCode=2 退出，并打「导入 akshare 失败」
         */
        public boolean isMissingModule() {
            return exitCode == 2 && output.contains("akshare");
        }

        private int intValue(String key) {
            String value = values.get(key);
            if (value == null || value.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
