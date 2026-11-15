package updater.http;

import updater.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class Http {
    public static String requrl(String url, String code, int ktype, long timestime, int limit) {
        return url + "&" + "code=" + code +
                "&" + "kType=" + ktype +
                "&" + "et=" + timestime +
                "&" + "limit=" + limit;
    }



    public static String reqUrl(String apiUrl, String refererUrl, String cookie) {
        HttpURLConnection connection = null;
        try {
            Log.outIndexData("apiUrl:" + apiUrl);
            // 创建URL对象
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();

            // 设置请求头，模拟浏览器访问
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");

            connection.setRequestProperty("Referer", refererUrl); // 关键：设置Referer
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
            connection.setRequestProperty("Cookie",cookie);
            // 获取响应代码
            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String result = response.toString();
                // 打印原始JSON响应以便调试
                if (Log.outDebugLog) {
                    Log.out("原始响应数据: " + result);
                }
              return result;

            } else {
               throw new RuntimeException("GET请求失败，响应代码: " + responseCode);
            }
        }  catch (IOException e){
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


}
