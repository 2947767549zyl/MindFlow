package com.mindflow.module.chat.service;

import com.mindflow.harness.execute.ToolFailureClassifier;
import com.mindflow.module.chat.entity.ToolExperience;
import com.mindflow.module.chat.repository.ToolExperienceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 工具失败经验库（episodic memory）：失败即经验。
 *
 * 写入点唯一在 ParallelToolRunner（ReAct / Plan / Team 三模式共用），
 * 读取点在每轮 ReAct 开始前，注入极短的"上次这里踩过坑"，让同类错误不再重复。
 */
@Service
public class ToolExperienceService {

    private static final Logger logger = LoggerFactory.getLogger(ToolExperienceService.class);

    /** 经验注入行数上限：这段文本会进入每一轮 LLM 调用，必须远小于提示词本体的量级 */
    private static final int MAX_GUIDANCE_LINES = 5;
    private static final int MAX_ERROR_CHARS = 500;
    private static final int MAX_HINT_CHARS = 300;

    private final ToolExperienceRepository repository;

    public ToolExperienceService(ToolExperienceRepository repository) {
        this.repository = repository;
    }

    public void recordFailure(String toolName, String errorText) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        ToolFailureClassifier.Category category = ToolFailureClassifier.classify(errorText);
        String signature = ToolFailureClassifier.signature(toolName, category);
        try {
            ToolExperience experience = repository.findBySignature(signature).orElseGet(ToolExperience::new);
            if (experience.getId() == null) {
                experience.setToolName(toolName);
                experience.setFailureCategory(category.name());
                experience.setSignature(signature);
                experience.setOccurrenceCount(1);
            } else {
                experience.setOccurrenceCount(experience.getOccurrenceCount() + 1);
            }
            experience.setLastErrorText(truncate(errorText, MAX_ERROR_CHARS));
            experience.setRecoveryHint(truncate(category.recoveryHint(), MAX_HINT_CHARS));
            repository.save(experience);
        } catch (Exception e) {
            logger.warn("记录工具失败经验失败: tool={}, error={}", toolName, e.getMessage());
        }
    }

    public String buildExperienceGuidance(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "";
        }
        try {
            List<ToolExperience> experiences = repository.findByToolNameInOrderByOccurrenceCountDesc(toolNames);
            if (experiences == null || experiences.isEmpty()) {
                return "";
            }
            StringBuilder guidance = new StringBuilder("## 工具失败经验（你在这些工具上踩过坑，请按恢复策略避开）\n");
            int lines = 0;
            for (ToolExperience experience : experiences) {
                if (lines >= MAX_GUIDANCE_LINES) {
                    break;
                }
                guidance.append("- ").append(experience.getToolName())
                        .append(" · ").append(labelOf(experience.getFailureCategory()))
                        .append("（已出现 ").append(experience.getOccurrenceCount()).append(" 次）：")
                        .append(experience.getRecoveryHint())
                        .append('\n');
                lines++;
            }
            return lines == 0 ? "" : guidance.toString().trim();
        } catch (Exception e) {
            logger.warn("构建工具失败经验指导失败: error={}", e.getMessage());
            return "";
        }
    }

    public List<ToolExperience> listExperiences() {
        try {
            return repository.findTop30ByOrderByOccurrenceCountDesc();
        } catch (Exception e) {
            logger.warn("读取工具失败经验失败: error={}", e.getMessage());
            return List.of();
        }
    }

    public static String labelOf(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return ToolFailureClassifier.Category.UNKNOWN.label();
        }
        try {
            return ToolFailureClassifier.Category.valueOf(categoryName).label();
        } catch (IllegalArgumentException e) {
            return categoryName;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
