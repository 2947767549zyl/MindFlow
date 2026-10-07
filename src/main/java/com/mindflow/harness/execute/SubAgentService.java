package com.mindflow.harness.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.harness.memory.ConversationHistoryCompactor;
import com.mindflow.harness.skill.SkillContextBuffer;
import com.mindflow.harness.skill.SkillIndexFormatter;
import com.mindflow.harness.skill.SkillRegistry;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 子代理工厂 - 按运行创建 {@link SubAgent} 实例，避免多用户共享可变对话历史。
 */
@Service
public class SubAgentService {

    private final LlmProviderRouter llmProviderRouter;
    private final AgentToolRegistry toolRegistry;
    private final ParallelToolRunner parallelToolRunner;
    private final SkillRegistry skillRegistry;
    private final SkillContextBuffer skillContextBuffer;
    private final ConversationHistoryCompactor historyCompactor;
    private final ObjectMapper objectMapper;

    @Value("${mindflow.react.max-rounds:10}")
    private int maxRounds;

    public SubAgentService(LlmProviderRouter llmProviderRouter,
                           AgentToolRegistry toolRegistry,
                           ParallelToolRunner parallelToolRunner,
                           SkillRegistry skillRegistry,
                           SkillContextBuffer skillContextBuffer,
                           ObjectMapper objectMapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.parallelToolRunner = parallelToolRunner;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.objectMapper = objectMapper;
        this.historyCompactor = new ConversationHistoryCompactor(llmProviderRouter);
    }

    public SubAgent newSubAgent(String name,
                                AgentRole role,
                                String requesterId,
                                String conversationId,
                                String generationId,
                                Consumer<String> contentSink,
                                Consumer<String> reasoningSink) {
        String skillIndex = SkillIndexFormatter.format(skillRegistry.enabledSkills());
        int compactTriggerTokens = llmProviderRouter.contextCompactTriggerTokens();
        return new SubAgent(name, role, requesterId, conversationId, generationId,
                llmProviderRouter, toolRegistry, parallelToolRunner, objectMapper,
                contentSink, reasoningSink, maxRounds,
                skillIndex, skillContextBuffer, historyCompactor, compactTriggerTokens);
    }
}
