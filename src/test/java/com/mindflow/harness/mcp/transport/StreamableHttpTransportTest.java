package com.mindflow.harness.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StreamableHttpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final ConcurrentLinkedQueue<MockResponse> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedDeque<RecordedRequest> recorded = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            recorded.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestHeaders()));
            MockResponse next = queue.poll();
            int code = next == null ? 500 : next.code();
            byte[] body = next == null || next.body() == null
                    ? new byte[0]
                    : next.body().getBytes(StandardCharsets.UTF_8);
            if (next != null) {
                next.headers().forEach(exchange.getResponseHeaders()::set);
            }
            if (body.length == 0) {
                exchange.sendResponseHeaders(code, -1);
            } else {
                exchange.sendResponseHeaders(code, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/mcp";
    }

    private void enqueue(MockResponse response) {
        queue.add(response);
    }

    private RecordedRequest takeRequest() {
        return recorded.poll();
    }

    private RecordedRequest takeRequest(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        RecordedRequest request = recorded.poll();
        while (request == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
            request = recorded.poll();
        }
        return request;
    }

    private record MockResponse(int code, Map<String, String> headers, String body) {
        static MockResponse jsonResponse(String body) {
            return new MockResponse(200, Map.of("Content-Type", "application/json"), body);
        }

        static MockResponse sseResponse(String body) {
            return new MockResponse(200, Map.of("Content-Type", "text/event-stream"), body);
        }

        static MockResponse withSession(String sessionId, String body) {
            return new MockResponse(200,
                    Map.of("Content-Type", "application/json", "Mcp-Session-Id", sessionId), body);
        }

        static MockResponse status(int code) {
            return new MockResponse(code, Map.of(), "");
        }
    }

    private record RecordedRequest(String method, Map<String, List<String>> headers) {
        String getHeader(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                        return entry.getValue().get(0);
                    }
                }
                return null;
            }
            return values.get(0);
        }
    }

    @Test
    void parsesPlainJsonResponseAndDispatchesToListeners() throws Exception {
        enqueue(MockResponse.jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(baseUrl(), Map.of());
        List<JsonNode> received = new ArrayList<>();
        transport.onReceive(received::add);

        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

        assertEquals(1, received.size());
        assertTrue(received.get(0).path("result").path("ok").asBoolean());
        assertEquals("http", transport.transportName());
    }

    @Test
    void parsesSseStreamWithMultipleDataMessages() throws Exception {
        String sseBody = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"step\":1}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"step\":2}}\n\n";
        enqueue(MockResponse.sseResponse(sseBody));

        StreamableHttpTransport transport = new StreamableHttpTransport(baseUrl(), Map.of());
        List<JsonNode> received = new ArrayList<>();
        transport.onReceive(received::add);

        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sub\"}"));

        assertEquals(2, received.size());
        assertEquals(1, received.get(0).path("result").path("step").asInt());
        assertEquals(2, received.get(1).path("result").path("step").asInt());
    }

    @Test
    void capturesAndReusesSessionIdHeader() throws Exception {
        enqueue(MockResponse.withSession("session-abc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        enqueue(MockResponse.jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(baseUrl(), Map.of());
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}"));
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"b\"}"));

        RecordedRequest first = takeRequest();
        assertNull(first.getHeader("Mcp-Session-Id"), "首次请求不应携带 session ID");
        RecordedRequest second = takeRequest();
        assertEquals("session-abc", second.getHeader("Mcp-Session-Id"),
                "拿到 session ID 后续请求必须带上");
    }

    @Test
    void closeIssuesDeleteWithSessionId() throws Exception {
        enqueue(MockResponse.withSession("session-xyz", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        enqueue(MockResponse.status(200));

        StreamableHttpTransport transport = new StreamableHttpTransport(baseUrl(), Map.of());
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}"));
        transport.close();

        takeRequest(); // 跳过 POST
        RecordedRequest deleteRequest = takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "close 应触发 DELETE 请求");
        assertEquals("DELETE", deleteRequest.method());
        assertEquals("session-xyz", deleteRequest.getHeader("Mcp-Session-Id"));
    }

    @Test
    void closeWithoutSessionIdSkipsDelete() {
        // 没 send 过任何请求，自然没有 session
        StreamableHttpTransport transport = new StreamableHttpTransport(baseUrl(), Map.of());
        transport.close();
        // server 没收到请求
        assertNull(recorded.poll(), "close 不应发出任何请求");
    }

    @Test
    void unsuccessfulResponseThrowsIoException() {
        enqueue(MockResponse.status(401));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                baseUrl(), Map.of("Authorization", "Bearer fake"));

        IOException ex = assertThrows(IOException.class,
                () -> transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")));
        assertTrue(ex.getMessage().contains("401"), "异常消息应含状态码: " + ex.getMessage());
    }

    @Test
    void customHeadersArePropagatedToServer() throws Exception {
        enqueue(MockResponse.jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

        StreamableHttpTransport transport = new StreamableHttpTransport(
                baseUrl(),
                Map.of("Authorization", "Bearer test-token", "X-Tenant", "acme"));
        transport.send(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"));

        RecordedRequest req = takeRequest();
        assertEquals("Bearer test-token", req.getHeader("Authorization"));
        assertEquals("acme", req.getHeader("X-Tenant"));
        assertEquals("application/json, text/event-stream", req.getHeader("Accept"));
        assertNotNull(req.getHeader("MCP-Protocol-Version"), "必须发送协议版本 header");
    }
}
