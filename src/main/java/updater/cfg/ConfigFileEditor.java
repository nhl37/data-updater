package updater.cfg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.properties 编辑工具
 *
 * 设计要点：
 * 1. 读取：按 ISO-8859-1 无损读取原始字节，保证注释、行顺序、未修改行完全不被破坏
 * 2. 解析：key 支持 = : 空白 三种分隔符；值支持行内注释(#, //, !)与引号(与原解析规则保持一致)
 * 3. 修改：只记录被修改的 key
 * 4. 保存：未修改行按原始字节写回；被修改行/新增行按 UTF-8 写回
 */
public class ConfigFileEditor {

    /** 原始行按 ISO-8859-1 读取，做到字节无损 */
    private static final Charset RAW_CHARSET = StandardCharsets.ISO_8859_1;

    private final File file;

    /** 原始文件行 */
    private final List<LineRecord> rawLines = new ArrayList<>();
    /** key -> 值(已去掉引号与行内注释，即运行时真实值) */
    private final Map<String, String> entries = new LinkedHashMap<>();
    /** key -> 文件中的原值 */
    private final Map<String, String> originals = new LinkedHashMap<>();
    /** key -> 原值是否带引号 */
    private final Map<String, Boolean> quotedKeys = new LinkedHashMap<>();

    private final Set<String> modifiedKeys = new LinkedHashSet<>();
    private final Set<String> addedKeys = new LinkedHashSet<>();
    private final Set<String> deletedKeys = new LinkedHashSet<>();

    public ConfigFileEditor(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /**
     * 读取配置文件
     */
    public void load() throws ConfigException {
        rawLines.clear();
        entries.clear();
        originals.clear();
        quotedKeys.clear();
        modifiedKeys.clear();
        addedKeys.clear();
        deletedKeys.clear();

        if (file == null) {
            throw new ConfigException("配置文件对象为null");
        }
        if (!file.exists()) {
            throw new ConfigException("配置文件不存在: " + file.getAbsolutePath());
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), RAW_CHARSET))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawLines.add(new LineRecord(line, RAW_CHARSET));
                parseEntry(line);
            }
        } catch (IOException e) {
            throw new ConfigException("读取配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 当前配置项快照，每项为 {key, value}
     */
    public List<String[]> getEntries() {
        List<String[]> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            list.add(new String[]{entry.getKey(), entry.getValue()});
        }
        return list;
    }

    /**
     * 是否存在未保存的修改
     */
    public boolean isDirty() {
        return !modifiedKeys.isEmpty() || !addedKeys.isEmpty() || !deletedKeys.isEmpty();
    }

    /**
     * 修改配置项的值
     */
    public void setValue(String key, String value) {
        if (key == null) {
            return;
        }
        String newValue = value == null ? "" : value;
        String oldValue = entries.get(key);
        if (oldValue != null && oldValue.equals(newValue) && !deletedKeys.contains(key)) {
            return;
        }
        entries.put(key, newValue);
        deletedKeys.remove(key);

        String original = originals.get(key);
        if (original != null && original.equals(newValue)) {
            modifiedKeys.remove(key);
        } else {
            modifiedKeys.add(key);
        }
    }

    /**
     * 新增配置项（若已存在则等价于修改值）
     */
    public void addEntry(String key, String value) {
        if (key == null) {
            return;
        }
        String newKey = key.trim();
        if (newKey.isEmpty()) {
            return;
        }
        if (entries.containsKey(newKey)) {
            setValue(newKey, value);
            return;
        }
        String newValue = value == null ? "" : value;
        entries.put(newKey, newValue);
        addedKeys.add(newKey);
        deletedKeys.remove(newKey);
    }

    /**
     * 删除配置项
     */
    public void deleteEntry(String key) {
        if (key == null || !entries.containsKey(key)) {
            return;
        }
        entries.remove(key);
        quotedKeys.remove(key);
        addedKeys.remove(key);
        modifiedKeys.remove(key);
        deletedKeys.add(key);
    }

    /**
     * 保存到文件：保留注释与顺序，只替换被修改的行，新项追加到文件末尾
     */
    public void save() throws ConfigException {
        List<LineRecord> newLines = new ArrayList<>();
        Set<String> handledKeys = new LinkedHashSet<>();

        for (LineRecord record : rawLines) {
            String line = record.text;
            String trimmed = line == null ? "" : line.trim();

            // 空行、注释行：原样保留
            if (trimmed.isEmpty() || trimmed.startsWith("#")
                    || trimmed.startsWith("!") || trimmed.startsWith("//")) {
                newLines.add(record);
                continue;
            }

            int sep = findSeparator(trimmed);
            if (sep <= 0) {
                newLines.add(record);
                continue;
            }

            String key = trimmed.substring(0, sep).trim();
            if (key.isEmpty()) {
                newLines.add(record);
                continue;
            }

            boolean firstOccurrence = !handledKeys.contains(key);

            if (deletedKeys.contains(key)) {
                handledKeys.add(key);
                continue; // 删除该键的所有行
            }

            if (modifiedKeys.contains(key)) {
                if (firstOccurrence) {
                    String head = buildHead(line, key);
                    String value = entries.get(key);
                    boolean forceQuote = Boolean.TRUE.equals(quotedKeys.get(key));
                    newLines.add(new LineRecord(head + safeValue(value, forceQuote), StandardCharsets.UTF_8));
                }
                handledKeys.add(key);
                continue; // 重复键行直接去掉，避免重复定义
            }

            newLines.add(record);
            handledKeys.add(key);
        }

        // 追加新增的配置项
        for (String key : addedKeys) {
            if (handledKeys.contains(key)) {
                continue;
            }
            String value = entries.get(key);
            newLines.add(new LineRecord(key + "=" + safeValue(value, false), StandardCharsets.UTF_8));
        }

        try (OutputStream output = new FileOutputStream(file)) {
            for (LineRecord record : newLines) {
                output.write(record.text.getBytes(record.charset));
                output.write('\n');
            }
            output.flush();
        } catch (IOException e) {
            throw new ConfigException("写入配置文件失败: " + e.getMessage());
        }

        // 保存成功后重新读取，清空修改标记
        load();
    }

    // ------------------------------------------------------------------ 解析相关

    private void parseEntry(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")
                || trimmed.startsWith("!") || trimmed.startsWith("//")) {
            return;
        }

        int sep = findSeparator(trimmed);
        if (sep <= 0) {
            return;
        }

        String key = trimmed.substring(0, sep).trim();
        if (key.isEmpty()) {
            return;
        }

        String rawValue = trimmed.substring(sep + 1);
        String noComment = removeInlineComment(rawValue).trim();
        boolean quoted = isQuoted(noComment);
        String value = decodeValue(unquote(noComment));

        entries.put(key, value);
        originals.put(key, value);
        quotedKeys.put(key, quoted);
    }

    /**
     * 查找 key 与 value 之间的分隔符位置（= : 或空白）
     */
    private static int findSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSeparatorChar(char c) {
        return c == '=' || c == ':' || Character.isWhitespace(c);
    }

    /**
     * 构造“key + 分隔符 + 缩进”的头部，保持与原行相同的分隔风格
     */
    private static String buildHead(String line, String key) {
        int keyStart = line.indexOf(key);
        if (keyStart < 0) {
            keyStart = 0;
        }
        int pos = keyStart + key.length();
        while (pos < line.length() && !isSeparatorChar(line.charAt(pos))) {
            pos++;
        }
        if (pos < line.length()) {
            pos++; // 跳过分隔符
        }
        int valueStart = pos;
        while (valueStart < line.length()
                && (line.charAt(valueStart) == ' ' || line.charAt(valueStart) == '\t')) {
            valueStart++;
        }
        return line.substring(0, valueStart);
    }

    private static String removeInlineComment(String value) {
        int index = -1;
        String[] markers = {" #", " //", " !"};
        for (String marker : markers) {
            int pos = value.indexOf(marker);
            if (pos >= 0 && (index < 0 || pos < index)) {
                index = pos;
            }
        }
        return index >= 0 ? value.substring(0, index) : value;
    }

    private static boolean isQuoted(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
    }

    private static String unquote(String value) {
        if (isQuoted(value)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 写回时的值处理：需要引号保护时加双引号
     */
    private static String safeValue(String value, boolean forceQuote) {
        String v = value == null ? "" : value;
        boolean needQuote = forceQuote
                || v.isEmpty()
                || !v.equals(v.trim())
                || v.contains(" #") || v.contains(" //") || v.contains(" !");
        if (!needQuote) {
            return v;
        }
        if (isQuoted(v)) {
            return v;
        }
        return "\"" + v + "\"";
    }

    /**
     * 把文件里的字节（以 ISO-8859-1 映射成字符串）尽量还原成可读文本
     */
    private static String decodeValue(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return rawValue;
        }
        boolean hasHighByte = false;
        for (int i = 0; i < rawValue.length(); i++) {
            if (rawValue.charAt(i) > 127) {
                hasHighByte = true;
                break;
            }
        }
        if (!hasHighByte) {
            return rawValue;
        }
        byte[] bytes = rawValue.getBytes(RAW_CHARSET);

        String utf8 = tryDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = tryDecode(bytes, Charset.forName("GBK"));
        if (gbk != null) {
            return gbk;
        }
        return rawValue;
    }

    private static String tryDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 一行内容 + 写回时使用的编码
     */
    private static class LineRecord {
        final String text;
        final Charset charset;

        LineRecord(String text, Charset charset) {
            this.text = text;
            this.charset = charset;
        }
    }
}