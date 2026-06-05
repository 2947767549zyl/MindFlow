package com.mindflow.llm;

import com.mindflow.config.PaiCliConfig;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = provider.toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = config.getModel(normalized);
        String baseUrl = config.getBaseUrl(normalized);

        int maxContextWindow = switch (normalized) {
            case "glm" -> 200_000;
            case "deepseek" -> 1_000_000;
            default -> 128_000;
        };

        // 如果配置了 baseUrl（LangChain4j 模式），优先使用 LangChain4jLlmClient
        if (baseUrl != null && !baseUrl.isBlank()) {
            return new LangChain4jLlmClient(
                    baseUrl, apiKey, model, normalized, maxContextWindow,
                    true, normalized.equals("glm") ? "glm-prompt-cache" : "automatic-prefix-cache");
        }

        // 否则走原有的手写客户端
        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) return client;

        for (String provider : new String[]{"glm", "deepseek"}) {
            client = create(provider, config);
            if (client != null) return client;
        }

        return null;
    }
}
