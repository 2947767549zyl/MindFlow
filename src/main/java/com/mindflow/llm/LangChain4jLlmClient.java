package com.mindflow.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 LangChain4j 的 LLM 客户端。
 *
 * 内部使用 OpenAiChatModel / OpenAiStreamingChatModel 包装，对接兼容 OpenAI 接口的模型
 * （GLM / DeepSeek 等），将现有 LlmClient.Message/Tool 与 LangChain4j 的
 * ChatMessage/ToolSpecification 相互转换。
 *
 * 当前未覆盖：
 * - reasoning_content（LangChain4j 未标准化，需要更低层实现）
 * - cachedInputTokens 精确提取（同需底层访问）
 */
public class LangChain4jLlmClient implements LlmClient {

    private static final long STREAM_TIMEOUT_SECONDS = 600;

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final String modelName;
    private final String providerName;
    private final int maxContextWindow;
    private final boolean supportsPromptCaching;
    private final String promptCacheMode;

    public LangChain4jLlmClient(String baseUrl, String apiKey, String model,
                                String provider, int maxContextWindow,
                                boolean supportsPromptCaching, String promptCacheMode) {
        this.modelName = model;
        this.providerName = provider;
        this.maxContextWindow = maxContextWindow;
        this.supportsPromptCaching = supportsPromptCaching;
        this.promptCacheMode = promptCacheMode;

        Duration timeout = Duration.ofSeconds(300);
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .timeout(timeout).maxRetries(2)
                .logRequests(false).logResponses(false)
                .build();

        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .timeout(timeout)
                .logRequests(false).logResponses(false)
                .build();
    }

    // ======================== LlmClient 接口 ========================

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        List<ChatMessage> lcMessages = convertMessages(messages);
        List<ToolSpecification> lcTools = convertTools(tools);

        if (listener == null || listener == StreamListener.NO_OP) {
            return nonStreamingChat(lcMessages, lcTools);
        }
        return streamingChat(lcMessages, lcTools, listener);
    }

    @Override public String getModelName() { return modelName; }
    @Override public String getProviderName() { return providerName; }
    @Override public int maxContextWindow() { return maxContextWindow; }
    @Override public boolean supportsPromptCaching() { return supportsPromptCaching; }
    @Override public String promptCacheMode() { return promptCacheMode; }

    // ======================== 非流式 ========================

    private ChatResponse nonStreamingChat(List<ChatMessage> messages, List<ToolSpecification> tools) throws IOException {
        try {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(messages);
            if (tools != null && !tools.isEmpty()) {
                builder.toolSpecifications(tools);
            }
            dev.langchain4j.model.chat.response.ChatResponse resp = chatModel.chat(builder.build());
            return toChatResponse(resp);
        } catch (Exception e) {
            throw new IOException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    // ======================== 流式 ========================

    private ChatResponse streamingChat(List<ChatMessage> messages, List<ToolSpecification> tools,
                                       StreamListener listener) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.ChatResponse> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler =
                new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String token) { listener.onContentDelta(token); }
                    @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse r) { result.set(r); latch.countDown(); }
                    @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
                };

        try {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(messages);
            if (tools != null && !tools.isEmpty()) {
                builder.toolSpecifications(tools);
            }
            streamingChatModel.chat(builder.build(), handler);

            if (!latch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("LLM 流式请求超时（" + STREAM_TIMEOUT_SECONDS + "秒）");
            }
            if (error.get() != null) {
                throw new IOException("LLM 流式调用失败: " + error.get().getMessage(), error.get());
            }
            dev.langchain4j.model.chat.response.ChatResponse resp = result.get();
            if (resp == null) throw new IOException("LLM 返回空响应");
            return toChatResponse(resp);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM 调用被中断", e);
        }
    }

    // ======================== 消息转换 ========================

    private List<ChatMessage> convertMessages(List<Message> messages) {
        List<ChatMessage> result = new ArrayList<>();
        Map<String, ToolExecutionRequest> pendingReqs = new HashMap<>();

        for (Message msg : messages) {
            switch (msg.role()) {
                case "system" -> result.add(new SystemMessage(msg.content()));
                case "user" -> result.add(new UserMessage(msg.content()));
                case "assistant" -> {
                    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                        List<ToolExecutionRequest> reqs = msg.toolCalls().stream()
                                .map(tc -> ToolExecutionRequest.builder()
                                        .id(tc.id()).name(tc.function().name())
                                        .arguments(tc.function().arguments()).build())
                                .toList();
                        reqs.forEach(r -> pendingReqs.put(r.id(), r));
                        result.add(new AiMessage(msg.content(), reqs));
                    } else {
                        result.add(new AiMessage(msg.content()));
                    }
                }
                case "tool" -> {
                    ToolExecutionRequest matched = pendingReqs.get(msg.toolCallId());
                    if (matched != null) {
                        result.add(ToolExecutionResultMessage.from(matched, msg.content()));
                    } else {
                        result.add(ToolExecutionResultMessage.from(msg.toolCallId(), "unknown", msg.content()));
                    }
                }
            }
        }
        return result;
    }

    // ======================== 工具定义转换 ========================

    private List<ToolSpecification> convertTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) return null;
        return tools.stream().map(this::toToolSpec).toList();
    }

    private ToolSpecification toToolSpec(Tool tool) {
        ToolSpecification.Builder b = ToolSpecification.builder()
                .name(tool.name()).description(tool.description());

        if (tool.parameters() != null && tool.parameters().isObject()) {
            JsonSchemaConverter.convert((ObjectNode) tool.parameters(), b);
        }
        return b.build();
    }

    // ======================== 响应转换 ========================

    private ChatResponse toChatResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
        AiMessage aiMsg = response.aiMessage();
        TokenUsage usage = response.tokenUsage();

        String content = aiMsg.text();
        List<ToolExecutionRequest> reqs = aiMsg.toolExecutionRequests();

        List<ToolCall> toolCalls = null;
        if (reqs != null && !reqs.isEmpty()) {
            toolCalls = reqs.stream()
                    .map(r -> new ToolCall(r.id(), new ToolCall.Function(r.name(), r.arguments())))
                    .toList();
        }

        return new ChatResponse(
                "assistant", content, null,
                toolCalls,
                usage != null ? usage.inputTokenCount() : 0,
                usage != null ? usage.outputTokenCount() : 0,
                0);
    }
}
