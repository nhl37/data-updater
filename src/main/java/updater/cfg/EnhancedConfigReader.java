package updater.cfg;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强版配置文件读取器
 * 支持特性：
 * - 多种分隔符: =, :, 空格
 * - 注释支持: #, !, //
 * - 编码自动检测
 * - 环境变量替换
 * - 多配置文件加载
 */
public class EnhancedConfigReader {

    private Map<String, String> configMap;
    private String configFilePath;

    public EnhancedConfigReader() {
        this.configMap = new ConcurrentHashMap<>();
    }

    /**
     * 从文件加载配置
     */
    public boolean load(String filePath) throws ConfigException {
        return load(filePath, StandardCharsets.UTF_8);
    }

    /**
     * 从文件加载配置（指定编码）
     */
    public boolean load(String filePath, java.nio.charset.Charset charset) throws ConfigException {
        this.configFilePath = filePath;
        File file = new File(filePath);

        if (!file.exists()) {
            throw new ConfigException("配置文件不存在: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), charset))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                processLine(line.trim(), lineNumber);
            }

            System.out.println("成功加载配置文件: " + filePath + ", 共 " + configMap.size() + " 个配置项");
            return true;

        } catch (IOException e) {
            throw new ConfigException("加载配置文件失败: " + e.getMessage());

        }
    }

    /**
     * 处理单行配置
     */
    private void processLine(String line, int lineNumber) {
        // 跳过空行和注释行
        if (line.isEmpty() || line.startsWith("#") ||
                line.startsWith("!") || line.startsWith("//")) {
            return;
        }

        // 支持多种分隔符
        String[] parts = line.split("[=:\\s]", 2);
        if (parts.length != 2) {
            System.err.println("第 " + lineNumber + " 行格式错误: " + line);
            return;
        }

        String key = parts[0].trim();
        String value = parts[1].trim();

        // 处理值中的注释（移除行内注释）
        value = removeInlineComments(value);

        // 处理环境变量替换 ${ENV_VAR}
        value = replaceEnvironmentVariables(value);

        // 处理引号
        value = unquoteValue(value);

        configMap.put(key, value);
    }

    /**
     * 移除行内注释
     */
    private String removeInlineComments(String value) {
        int commentIndex = -1;

        // 查找注释开始位置
        if (value.contains(" #")) {
            commentIndex = value.indexOf(" #");
        } else if (value.contains(" //")) {
            commentIndex = value.indexOf(" //");
        } else if (value.contains(" !")) {
            commentIndex = value.indexOf(" !");
        }

        if (commentIndex != -1) {
            return value.substring(0, commentIndex).trim();
        }

        return value;
    }

    /**
     * 替换环境变量
     */
    private String replaceEnvironmentVariables(String value) {
        if (!value.contains("${")) {
            return value;
        }

        StringBuilder result = new StringBuilder();
        int start = 0;
        int pos;

        while ((pos = value.indexOf("${", start)) != -1) {
            result.append(value, start, pos);
            int end = value.indexOf("}", pos);

            if (end == -1) {
                // 没有闭合的 }
                result.append(value.substring(pos));
                break;
            }

            String varName = value.substring(pos + 2, end);
            String varValue = System.getenv(varName);

            if (varValue != null) {
                result.append(varValue);
            } else {
                // 环境变量不存在，保持原样
                result.append("${").append(varName).append("}");
            }

            start = end + 1;
        }

        result.append(value.substring(start));
        return result.toString();
    }

    /**
     * 处理引号包围的值
     */
    private String unquoteValue(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 获取字符串值
     */
    public String getString(String key) {
        return configMap.get(key);
    }

    /**
     * 获取字符串值，带默认值
     */
    public String getString(String key, String defaultValue) {
        return configMap.getOrDefault(key, defaultValue);
    }

    /**
     * 获取整数值
     */
    public int getInt(String key) {
        return getInt(key, 0);
    }

    /**
     * 获取整数值，带默认值
     */
    public int getInt(String key, int defaultValue) {
        String value = configMap.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("配置项 " + key + " 的值 '" + value + "' 不是有效的整数");
            return defaultValue;
        }
    }

    /**
     * 获取布尔值
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    /**
     * 获取布尔值，带默认值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = configMap.get(key);
        if (value == null) return defaultValue;

        String lowerValue = value.toLowerCase();
        return lowerValue.equals("true") || lowerValue.equals("yes") ||
                lowerValue.equals("1") || lowerValue.equals("on");
    }

    /**
     * 获取长整型值
     */
    public long getLong(String key) {
        return getLong(key, 0L);
    }

    /**
     * 获取长整型值，带默认值
     */
    public long getLong(String key, long defaultValue) {
        String value = configMap.get(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            System.err.println("配置项 " + key + " 的值 '" + value + "' 不是有效的长整数");
            return defaultValue;
        }
    }

    /**
     * 获取双精度浮点值
     */
    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    /**
     * 获取双精度浮点值，带默认值
     */
    public double getDouble(String key, double defaultValue) {
        String value = configMap.get(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            System.err.println("配置项 " + key + " 的值 '" + value + "' 不是有效的浮点数");
            return defaultValue;
        }
    }

    /**
     * 获取字符串列表（逗号分隔）
     */
    public List<String> getStringList(String key) {
        return getStringList(key, ",");
    }

    /**
     * 获取字符串列表（自定义分隔符）
     */
    public List<String> getStringList(String key, String delimiter) {
        String value = configMap.get(key);
        if (value == null) return new ArrayList<>();

        String[] parts = value.split(delimiter);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 获取整数列表
     */
    public Integer[] getIntList(String key) throws ConfigException {
        List<String> stringList = getStringList(key);
        Integer[] result = new Integer[stringList.size()];

        for (int i =0; i < stringList.size();i++) {
            try {
                String item =  stringList.get(i);
                result[i] = Integer.parseInt(item.trim());
            } catch (NumberFormatException e) {
                throw new ConfigException("列表项 '" + key + "' 不是有效的整数 列表");
            }
        }

        return result;
    }

    /**
     * 检查是否包含某个键
     */
    public boolean containsKey(String key) {
        return configMap.containsKey(key);
    }

    /**
     * 获取所有配置键
     */
    public Set<String> getKeys() {
        return configMap.keySet();
    }

    /**
     * 获取配置数量
     */
    public int size() {
        return configMap.size();
    }

    /**
     * 清除所有配置
     */
    public void clear() {
        configMap.clear();
    }

    /**
     * 重新加载配置
     */
    public boolean reload() throws ConfigException {
        if (configFilePath == null) {
            System.err.println("没有指定配置文件路径，无法重新加载");
            return false;
        }

        clear();
        return load(configFilePath);
    }

    /**
     * 打印所有配置（用于调试）
     */
    public void printAll() {
        System.out.println("=== 配置文件内容 (" + configFilePath + ") ===");
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            System.out.println(entry.getKey() + " = " + entry.getValue());
        }
        System.out.println("=====================================");
    }
}
