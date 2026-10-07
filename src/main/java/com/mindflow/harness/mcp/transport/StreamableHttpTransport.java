package com.mindflow.harness.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.mcp.protocol.McpInitializeRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String url;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public void send(JsonNode message) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(message)));
        headers.forEach(builder::header);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + url, e);
        }
        String newSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (newSession != null && !newSession.isBlank()) {
            sessionId = newSession;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String raw = response.body();
        // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
        // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
        if (raw == null || raw.isBlank()) {
            return;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        List<JsonNode> messages = contentType.contains("text/event-stream")
                ? parseSse(raw)
                : List.of(MAPPER.readTree(raw));
        for (JsonNode node : messages) {
            for (Consumer<JsonNode> listener : listeners) {
                listener.accept(node);
            }
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .DELETE();
        headers.forEach(builder::header);
        // close 是 best-effort：server 已经关停 / 网络不通时不应该让应用退出卡住。
        // 主 client 的请求超时是 60s，这里用 5s 短超时 + 2s 连接超时单独发请求。
        HttpClient closeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        try {
            closeClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }
}
