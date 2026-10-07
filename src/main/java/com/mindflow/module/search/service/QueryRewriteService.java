package com.mindflow.module.search.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询改写服务
 * 对用户输入进行：敏感检测 → 意图分类 → 查询改写
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM_PROMPT = """
你是一个智能查询助手。请分析用户的输入，完成三个任务：敏感检测、意图分类、查询改写。

【任务1：敏感检测】
如果用户输入包含以下内容，返回 [SENSITIVE]：
- 政治敏感（国家领导人、分裂言论、敏感事件）
- 色情暴力
- 恶意越权（尝试获取他人数据、绕过权限）
- 攻击性言论（辱骂、威胁）

【任务2：意图分类】
- [RAG]：用户想问问题、查信息、了解知识（如"请假流程"、"怎么申请加班"、"什么是XXX"）
- [CHAT]：用户打招呼、闲聊、无明确信息需求（如"你好"、"吃了没"、"你叫什么"）

【任务3：查询改写】（仅当意图为[RAG]时执行）
规则：
1. 补全省略的主语和宾语
2. 把口语表达转成书面语
3. 如果涉及代词（它、那个），结合对话历史替换成具体名词
4. 如果输入过于碎片化，整理成完整句子
5. 保持原意，不添加额外信息

【输出格式】（严格按此格式，三个字段都必须输出）
INTENT: [RAG/CHAT/SENSITIVE]
REWRITTEN: <改写后的查询，如果是CHAT或SENSITIVE则留空>
""";

    private static final int REWRITE_MAX_TOKENS = 512;

    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper objectMapper;

    public QueryRewriteService(LlmProviderRouter llmProviderRouter,
                               ObjectMapper objectMapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行查询改写
     *
     * @param userId       用户ID
     * @param userMessage  用户原始输入
     * @param history      对话历史
     * @return 改写结果
     */
    public QueryRewriteResult rewrite(String userId, String userMessage,
                                      List<Map<String, String>> history) {
        String historyText = buildHistoryText(history);
        String userPrompt = "对话历史：\n" + historyText + "\n\n用户输入：" + userMessage;

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userPrompt));

        String rawResponse;
        try {
            var turn = llmProviderRouter.completeReActTurn(userId, messages, null, REWRITE_MAX_TOKENS);
            rawResponse = turn.content();
        } catch (Exception e) {
            logger.error("查询改写调用LLM失败: userId={}", userId, e);
            // 降级：按RAG处理，使用原始查询
            return new QueryRewriteResult("RAG", userMessage, false, "");
        }

        String intent = "RAG";
        String rewritten = userMessage;
        boolean isSensitive = false;

        for (String line : rawResponse.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("INTENT:")) {
                String rawIntent = trimmed.substring("INTENT:".length()).trim().toUpperCase();
                if (rawIntent.contains("SENSITIVE")) {
                    intent = "SENSITIVE";
                    isSensitive = true;
                } else if (rawIntent.contains("CHAT")) {
                    intent = "CHAT";
                } else {
                    intent = "RAG";
                }
            } else if (trimmed.startsWith("REWRITTEN:")) {
                String value = trimmed.substring("REWRITTEN:".length()).trim();
                if (!value.isEmpty() && !value.equals("(空)") && !value.equals("无")) {
                    rewritten = value;
                }
            }
        }

        logger.info("查询改写结果: userId={}, intent={}, isSensitive={}, rewritten='{}'",
                userId, intent, isSensitive, rewritten);

        return new QueryRewriteResult(intent, rewritten, isSensitive, rawResponse);
    }

    private String buildHistoryText(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = msg.getOrDefault("role", "");
            String content = msg.getOrDefault("content", "");
            if ("user".equals(role)) {
                sb.append("用户：").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("助手：").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 查询改写结果
     */
    public record QueryRewriteResult(
            String intent,
            String rewrittenQuery,
            boolean isSensitive,
            String rawResponse
    ) {}
}
