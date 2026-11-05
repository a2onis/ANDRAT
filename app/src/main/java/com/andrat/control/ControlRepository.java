package com.andrat.control;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Small Firebase Realtime Database REST client; no SDK or account credential is embedded in the APK. */
public final class ControlRepository {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String value = endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - 5);
        }
        return value;
    }

    public List<RemoteCommand> readCommands(String endpoint, String deviceId, String token)
            throws IOException, JSONException {
        String response = request("GET", commandRoot(endpoint, deviceId, token), null);
        if (response == null || response.trim().isEmpty() || "null".equals(response.trim())) {
            return new ArrayList<>();
        }

        JSONObject root = new JSONObject(response);
        List<RemoteCommand> commands = new ArrayList<>();
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject raw = root.optJSONObject(key);
            if (raw != null) {
                commands.add(RemoteCommand.from(key, raw));
            }
        }
        Collections.sort(commands, new Comparator<RemoteCommand>() {
            @Override
            public int compare(RemoteCommand left, RemoteCommand right) {
                return Long.compare(left.createdAt, right.createdAt);
            }
        });
        return commands;
    }

    public void writeStatus(String endpoint, String deviceId, JSONObject status) throws IOException {
        request("PUT", deviceRoot(endpoint, deviceId) + "/status.json", status.toString());
    }

    public void writeAck(String endpoint, String deviceId, String token, String commandId, JSONObject ack)
            throws IOException {
        request("PUT", ackRoot(endpoint, deviceId, token) + "/" + segment(commandId)
                + ".json", ack.toString());
    }

    public void deleteCommand(String endpoint, String deviceId, String token, String commandId)
            throws IOException {
        request("DELETE", commandRoot(endpoint, deviceId, token) + "/" + segment(commandId) + ".json", null);
    }

    private String deviceRoot(String endpoint, String deviceId) {
        return normalizeEndpoint(endpoint) + "/devices/" + segment(deviceId);
    }

    private String commandRoot(String endpoint, String deviceId, String token) {
        return deviceRoot(endpoint, deviceId) + "/inbox/" + segment(token);
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    private String request(String method, String endpoint, String body) throws IOException {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IOException("Database endpoint is empty");
        }
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : readAll(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("Database request failed (" + status + ")"
                    + (response.isEmpty() ? "" : ": " + response));
        }
        return response;
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        return output.toString();
    }

    public static final class RemoteCommand {
        public final String id;
        public final String type;
        public final JSONObject payload;
        public final long createdAt;

        private RemoteCommand(String id, String type, JSONObject payload, long createdAt) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.createdAt = createdAt;
        }

        static RemoteCommand from(String key, JSONObject raw) throws JSONException {
            String id = raw.optString("id", key);
            String type = raw.optString("type", "");
            JSONObject payload = raw.optJSONObject("payload");
            if (payload == null) {
                payload = new JSONObject();
            }
            long createdAt = raw.optLong("createdAt", 0L);
            return new RemoteCommand(id, type, payload, createdAt);
        }
    }
}
