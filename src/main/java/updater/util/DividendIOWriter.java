package updater.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.List;

public class DividendIOWriter {
    public static void write(String filename, String[] lines) throws IOException {
        BufferedWriter bw = new BufferedWriter(new java.io.FileWriter(filename));

        for (String line:lines) {
            bw.write(line);
        }
        bw.flush();
        bw.close();
    }

    public static String getFilePath(String code, String urlname) throws ParseException {
        String path = "data/" + urlname;
        File file = new File(path);
        if(file.isFile() || !file.exists()) {
            file.mkdir();
        }

        return path + "/" + code+"." + urlname;
    }

    public static  void writeDividend(String code,String urlname, List<String[]> resultArr) throws ParseException, IOException {

        String tofile = DividendIOWriter.getFilePath(code,urlname);

        BufferedWriter bw = new BufferedWriter(new java.io.FileWriter(tofile));
        for (String[] data : resultArr) {
            //股票代码 日期 除权内容
            String row = String.format("%s,%d,%s\n",
                    code, Integer.valueOf(data[1]), data[0]);
            bw.write(row);
        }

        bw.flush();
        bw.close();
    }

    public static  void writeCodeList(List<String> codeList,String filename) throws ParseException, IOException {

        BufferedWriter bw = new BufferedWriter(new java.io.FileWriter(filename));
        for (String code : codeList) {
            //股票代码 日期 除权内容
            String row = String.format("%s\n", code);
            bw.write(row);
        }

        bw.flush();
        bw.close();
    }
}
