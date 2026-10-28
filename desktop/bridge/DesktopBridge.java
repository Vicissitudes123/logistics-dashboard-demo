package com.animeshelf.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded UTF-8 stdin request per process. Login state never appears in argv or logs. */
public final class DesktopBridge {
    public static void main(String[] ignored) {
        JSONObject answer = new JSONObject();
        try {
            String raw = readInput(System.in);
            JSONObject request = new JSONObject(raw);
            BiliClient client = new BiliClient(request.optString("cookie", ""));
            Object result;
            switch (request.getString("operation")) {
                case "resolve": result = client.resolve(request.getString("input")).toJson(); break;
                case "qualities":
                    List<Models.Quality> choices = client.qualities(Models.Episode.fromJson(request.getJSONObject("episode")));
                    JSONArray values = new JSONArray();
                    for (Models.Quality q : choices) values.put(q.toJson());
                    result = values; break;
                case "verify_login": result = client.verifyLogin(); break;
                case "refresh_series": result = client.refreshSeries(Models.Series.fromJson(request.getJSONObject("series"))).toJson(); break;
                case "refresh": result = client.refresh(Models.Episode.fromJson(request.getJSONObject("episode")), request.getString("qualityId")).toJson(); break;
                default: throw new IllegalArgumentException();
            }
            answer.put("ok", true).put("result", result);
        } catch (Exception failure) {
            String message;
            if (failure instanceof BiliClient.ClientException) message = failure.getMessage();
            else if (failure instanceof java.net.SocketTimeoutException) message = "请求超时，请检查网络后重试";
            else if (failure instanceof javax.net.ssl.SSLException) message = "安全连接失败，请检查系统时间或网络";
            else if (failure instanceof java.io.IOException) message = "网络请求未完成，请检查网络后重试";
            else message = "解析请求或返回数据格式无效，请重新导入或重试";
            answer.put("ok", false).put("message", message);
        }
        PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        output.println(answer.toString());
    }
    private static String readInput(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 1024 * 1024) throw new IllegalArgumentException();
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
