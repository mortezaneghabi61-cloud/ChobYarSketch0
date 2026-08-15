package ir.chobyar.sketch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AiClient {

    public interface Callback {
        void onSuccess(AiReply reply);
        void onError(String message);
    }

    public static class AiReply {
        public final String message;
        public final List<String> commands;

        AiReply(String message, List<String> commands) {
            this.message = message == null ? "" : message;
            this.commands = commands == null ? new ArrayList<>() : commands;
        }
    }

    public void ask(String endpoint, String prompt, String selectedInfo, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(endpoint);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(90_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("prompt", prompt == null ? "" : prompt);
                body.put("selected", selectedInfo == null ? "" : selectedInfo);
                body.put("client", "ChobYar-Android");

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String text = readAll(stream);

                if (code < 200 || code >= 300) {
                    callback.onError("خطای سرور AI (" + code + "): " + safeMessage(text));
                    return;
                }

                JSONObject json = new JSONObject(text);
                String message = json.optString("message", "");
                JSONArray commandsJson = json.optJSONArray("commands");
                List<String> commands = new ArrayList<>();
                if (commandsJson != null) {
                    for (int i = 0; i < commandsJson.length(); i++) {
                        String command = commandsJson.optString(i, "").trim();
                        if (!command.isEmpty()) commands.add(command);
                    }
                }
                callback.onSuccess(new AiReply(message, commands));
            } catch (Exception e) {
                callback.onError("ارتباط با AI برقرار نشد: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String safeMessage(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "پاسخی دریافت نشد";
        try {
            JSONObject json = new JSONObject(raw);
            return json.optString("error", raw);
        } catch (Exception ignored) {
            return raw.length() > 220 ? raw.substring(0, 220) : raw;
        }
    }
}
