package com.mindflow.module.search.service;

import com.mindflow.module.search.dto.SearchResult;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务
 * 使用LLM对候选文档进行精排
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    private static final int RERANK_MAX_TOKENS = 1024;

    private static final String RERANK_SYSTEM_PROMPT = """
你是一个文档相关性评估专家。请根据用户的查询，对给定的文档片段进行相关性打分。

打分要求：
- 5分：完全匹配，文档直接回答了用户的查询
- 4分：高度相关，文档提供了查询所需的核心信息
- 3分：部分相关，文档提到了相关主题但未直接回答问题
- 2分：低度相关，文档仅包含少量相关关键词
- 1分：不相关，文档内容与查询无关

输出格式（只输出JSON数组，不输出其他内容）：
[index, score]
其中index是文档片段的序号（从0开始），score是1-5的打分。

示例：
[2, 5]
[0, 4]
[1, 3]
""";

    private final LlmProviderRouter llmProviderRouter;

    public RerankService(LlmProviderRouter llmProviderRouter) {
        this.llmProviderRouter = llmProviderRouter;
    }

    /**
     * 对候选结果进行重排序
     *
     * @param userId     用户ID
     * @param query      原始查询
     * @param candidates 候选文档列表
     * @param topK       返回的最终结果数
     * @return 重排序后的结果
     */
    public List<SearchResult> rerank(String userId, String query,
                                     List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (candidates.size() <= topK) {
            return candidates;
        }

        try {
            StringBuilder docBuilder = new StringBuilder();
            docBuilder.append("用户查询：").append(query).append("\n\n");
            docBuilder.append("需要评估的文档片段：\n");
            for (int i = 0; i < candidates.size(); i++) {
                SearchResult result = candidates.get(i);
                String text = result.getMatchedChunkText() != null
                        ? result.getMatchedChunkText()
                        : result.getTextContent();
                if (text != null && text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                docBuilder.append("--- 片段 ").append(i).append(" ---\n");
                docBuilder.append(text != null ? text : "(空)").append("\n\n");
            }
            docBuilder.append("请对以上文档片段进行相关性打分，输出每个片段的序号和分数。");

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", RERANK_SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", docBuilder.toString()));

            var turn = llmProviderRouter.completeReActTurn(userId, messages, null, RERANK_MAX_TOKENS);
            String response = turn.content();

            Map<Integer, Double> scores = parseScores(response, candidates.size());

            List<SearchResult> reranked = candidates.stream()
                    .map(r -> new ScoredResult(r, scores.getOrDefault(candidates.indexOf(r), 3.0d)))
                    .sorted((a, b) -> Double.compare(b.score, a.score))
                    .map(r -> r.result)
                    .collect(Collectors.toList());

            logger.info("重排序完成: candidates={}, topK={}", candidates.size(), topK);
            return reranked.subList(0, Math.min(topK, reranked.size()));

        } catch (Exception e) {
            logger.error("重排序失败，返回原始排序", e);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private Map<Integer, Double> parseScores(String response, int totalCount) {
        Map<Integer, Double> scores = new HashMap<>();
        for (int i = 0; i < totalCount; i++) {
            scores.put(i, 3.0d);
        }
        try {
            for (String line : response.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.contains(",") && trimmed.endsWith("]")) {
                    String inner = trimmed.substring(1, trimmed.length() - 1);
                    String[] parts = inner.split(",");
                    if (parts.length == 2) {
                        int index = Integer.parseInt(parts[0].trim());
                        double score = Double.parseDouble(parts[1].trim());
                        if (index >= 0 && index < totalCount && score >= 1 && score <= 5) {
                            scores.put(index, score);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析重排序分数失败，使用默认分数", e);
        }
        return scores;
    }

    private record ScoredResult(SearchResult result, double score) {}
}
