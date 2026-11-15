package updater.util;

import updater.fetchdata.ReadDataException;
import updater.log.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Collectors;

public class FileTool {
    /**
     * 搜索指定目录下以指定前缀开头的文件
     * @param directoryPath 目录路径
     * @param prefix 文件前缀
     * @return 符合条件的文件列表（只包含文件名）
     * @throws IOException 如果目录不存在或无法访问
     */
    public static List<String> searchFilesByPrefix(String directoryPath, String prefix) throws IOException {
        Path dir = Paths.get(directoryPath);

        // 使用Files.list获取目录中的文件流
        return Files.list(dir)
                .filter(Files::isRegularFile)  // 只处理普通文件，排除目录
                .map(Path::getFileName)        // 获取文件名
                .map(Path::toString)           // 转换为字符串
                .filter(fileName -> fileName.startsWith(prefix))  // 过滤以prefix开头的文件
                .sorted()                      // 按名称排序
                .collect(Collectors.toList());
    }

    /**
     * 使用 NIO Files.walkFileTree 删除目录（推荐方式）
     */
    public static boolean deleteDirectoryNIO(String directoryPath) throws IOException {
        Path directory = Paths.get(directoryPath);

        if (!Files.exists(directory)) {
            System.out.println("目录不存在: " + directoryPath);
            return false;
        }

        if (!Files.isDirectory(directory)) {
            System.out.println("路径不是目录: " + directoryPath);
            return false;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
//                System.out.println("删除文件: " + file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc == null) {
                    Files.delete(dir);
//                    System.out.println("删除目录: " + dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw exc;
                }
            }
        });

        Log.info("成功删除目录: " + directoryPath);
        return true;
    }

    /**
     * 获取目录中所有文件和文件夹（基础方法）
     */
    public static String[] listFileNames(String directoryPath) throws ReadDataException {
        File directory = new File(directoryPath);

        if (!directory.exists()) {
            throw new ReadDataException("目录不存在: " + directoryPath);

        }

        if (!directory.isDirectory()) {
            throw new ReadDataException("路径不是目录: " + directoryPath);
        }

        String[] files = directory.list();

        if (files == null) {
            throw new ReadDataException("无法访问目录: " + directoryPath);
        }

        return files;

    }

    /**
     * 获取目录中所有文件和文件夹（基础方法）
     */
    public static File[] listFilesBasic(String directoryPath) throws ReadDataException {
        File directory = new File(directoryPath);

        if (!directory.exists()) {
            throw new ReadDataException("目录不存在: " + directoryPath);

        }

        if (!directory.isDirectory()) {
            throw new ReadDataException("路径不是目录: " + directoryPath);
        }

        File[] files = directory.listFiles();

        if (files == null) {
            throw new ReadDataException("无法访问目录: " + directoryPath);
        }

        return files;

    }

    public static String getCodeByDbname(String fileName) {
        String code = null;
        if(fileName.endsWith(".db"))
        {
             code = fileName.substring(0, fileName.length()-3);
        }
        return code;
    }

    public static String getCodeByFilename(String fileName, String suffix) {
        String code = null;
        if(fileName.endsWith("." + suffix))
        {
            code = fileName.substring(0, fileName.length() - (suffix.length() + 1) );
        }
        return code;
    }

}
