package updater.fetchdata.tdx.finance;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用虚拟环境中的 python 下载/解析本地数据来源财报数据
 *
 * 解释器取自 config.properties:
 *   tdx.dividend.pythonExe=~/.venv/fetch/bin/python
 */
public class FinancePythonRunner {

    private static final String SUCCESS_PREFIX = "SUCCESS:";

    /**
     * 只探测最新报告期(不下载、不解析)
     */
    public static Result runCheckOnly() throws Exception {
        return execute(true, null);
    }

    /**
     * 只探测指定报告期是否存在于服务器(不下载、不解析)
     */
    public static Result runCheckOnly(String reportDate) throws Exception {
        return execute(true, reportDate);
    }

    /**
     * 下载并解析最新报告期
     */
    public static Result runFetch() throws Exception {
        return execute(false, null);
    }

    /**
     * 下载并解析指定报告期
     */
    public static Result runFetch(String reportDate) throws Exception {
        return execute(false, reportDate);
    }

    private static Result execute(boolean checkOnly, String reportDate) throws Exception {
        FinanceFetchConfig config = FinanceFetchConfig.getInstance();
        String workDir = updater.cfg.ProgramHome.path();

        // ===== 1. 校验虚拟环境 python 解释器 =====
        String configuredExe = config.pythonExe();
        File exeFile = config.pythonExeFile();
        if (exeFile != null) {
            if (!exeFile.isFile()) {
                throw new IOException("python 解释器不存在: " + exeFile.getAbsolutePath()
                        + "，请检查 config.properties 中的 "
                        + FinanceFetchConfig.KEY_PYTHON_EXE);
            }
            if (!exeFile.canExecute()) {
                throw new IOException("python 解释器没有执行权限: " + exeFile.getAbsolutePath()
                        + "，请执行: chmod +x " + exeFile.getAbsolutePath());
            }
        }
        String pythonCommand = (exeFile != null) ? exeFile.getAbsolutePath() : configuredExe;
        System.out.println("python 解释器: " + pythonCommand
                + (config.isVirtualEnvPython() ? " (虚拟环境)" : ""));

        // ===== 2. 校验脚本与输出路径 =====
        // 脚本按「工作目录 → jar 目录 → jar 上级 → jar 内自带」找，jar 被挪到别处也能跑
        File scriptFile = updater.cfg.ScriptPaths.resolve(config.pythonFile());
        if (scriptFile == null || !scriptFile.isFile()) {
            throw new IOException("python 脚本不存在: "
                    + (scriptFile == null ? config.pythonFile() : scriptFile.getAbsolutePath()));
        }

        File csvFile = new File(config.csvFile());
        if (!csvFile.isAbsolute()) {
            csvFile = new File(workDir, config.csvFile());
        }
        File metaFile = new File(config.metaFile());
        if (!metaFile.isAbsolute()) {
            metaFile = new File(workDir, config.metaFile());
        }

        // ===== 3. 组装命令 =====
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.add("-u");
        command.add(scriptFile.getAbsolutePath());
        command.add("--out-dir");
        command.add(new File(config.downloadDir()).getAbsolutePath());
        command.add("--csv-file");
        command.add(csvFile.getAbsolutePath());
        command.add("--meta-file");
        command.add(metaFile.getAbsolutePath());
        command.add("--min-size");
        command.add(String.valueOf(config.minValidSize()));
        if (reportDate != null && !reportDate.trim().isEmpty()) {
            command.add("--report-date");
            command.add(reportDate.trim());
        }
        if (checkOnly) {
            command.add("--check-only");
        }
        if (config.debug()) {
            command.add("--debug");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(workDir));
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().put("PYTHONUNBUFFERED", "1");
        if (exeFile != null) {
            File venvRoot = exeFile.getParentFile() == null ? null : exeFile.getParentFile().getParentFile();
            if (venvRoot != null) {
                String sitePackages = new File(venvRoot,
                        "lib/python3.11/site-packages").getAbsolutePath();
                String pythonPath = processBuilder.environment().get("PYTHONPATH");
                processBuilder.environment().put("PYTHONPATH",
                        pythonPath == null || pythonPath.isEmpty()
                                ? sitePackages
                                : sitePackages + File.pathSeparator + pythonPath);
            }
        }

        System.out.println("执行命令: " + String.join(" ", command));

        // ===== 4. 执行并解析输出 =====
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Result result = new Result();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("[python] " + line);
                result.parseLine(line);
            }
        }

        int exitCode = process.waitFor();
        result.finish(exitCode, output.toString(), pythonCommand);
        if (exitCode != 0) {
            System.err.println("python 执行失败, exitCode=" + exitCode
                    + ", 解释器=" + pythonCommand);
            System.err.println("请确认虚拟环境已安装依赖: "
                    + pythonCommand + " -m pip install mootdx pytdxdata pandas");
        }
        return result;
    }

    /**
     * python 脚本执行结果
     */
    public static class Result {

        private int exitCode = -1;
        private String output = "";
        private String successPath;
        private String pythonCommand;
        private final Map<String, String> values = new LinkedHashMap<>();

        void parseLine(String line) {
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

        void finish(int exitCode, String output, String pythonCommand) {
            this.exitCode = exitCode;
            this.output = output;
            this.pythonCommand = pythonCommand;
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

        public Map<String, String> getValues() {
            return values;
        }

        public String getPythonExe() {
            String value = values.get("PYTHON_EXE");
            if (value != null && !value.isEmpty()) {
                return value;
            }
            return pythonCommand;
        }

        public String getReportDate() {
            return values.get("REPORT_DATE");
        }

        public String getSourceFile() {
            return values.get("SOURCE_FILE");
        }

        public String getAvailableDates() {
            return values.get("AVAILABLE_DATES");
        }

        public String getCsvFile() {
            String file = values.get("CSV_FILE");
            if (file == null || file.isEmpty()) {
                return successPath;
            }
            return file;
        }

        public long getFileSize() {
            return parseLong(values.get("FILE_SIZE"));
        }

        public int getRowCount() {
            String value = values.get("ROW_COUNT");
            if (value == null || value.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean isDownloadSkipped() {
            return "1".equals(values.get("DOWNLOAD_SKIPPED"));
        }

        private long parseLong(String value) {
            if (value == null || value.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
