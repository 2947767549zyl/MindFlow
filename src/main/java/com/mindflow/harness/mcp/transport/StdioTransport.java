package com.mindflow.harness.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private volatile boolean closed;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        this.process = builder.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("MCP stdio transport already closed");
        }
        stdin.write(MAPPER.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        closed = true;
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）。
        // shutdown 通知由 McpClient.close 在调本方法之前发出，子进程拿到 EOF 后通常会立即退出。
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree();
            return;
        }
        // 优雅窗口超时，发 SIGTERM
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                killTree();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree();
        }
    }

    /**
     * 终止整棵进程树。Windows 的 destroy()/destroyForcibly() 只结束直接子进程，
     * 而 npx/uvx 经 cmd.exe 包装后 node/python 是孙进程，仅关直接子进程会留下孤儿；
     * 故先 taskkill /F /T 杀树，再 destroyForcibly 兜底。
     */
    private void killTree() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win")) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // taskkill 不可用（如受限环境）时回退到 destroyForcibly
            }
        }
        process.destroyForcibly();
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode message = MAPPER.readTree(line);
                    for (Consumer<JsonNode> listener : listeners) {
                        listener.accept(message);
                    }
                }
            } catch (Exception e) {
                appendStderr("[mindflow] stdout reader stopped: " + e.getMessage());
            }
        }, "mindflow-mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                appendStderr("[mindflow] stderr reader stopped: " + e.getMessage());
            }
        }, "mindflow-mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            while (stderrRing.size() >= MAX_STDERR_LINES) {
                stderrRing.removeFirst();
            }
            stderrRing.addLast(line);
        }
    }
}
