package com.mindflow.module.chat.agent;

import com.mindflow.module.chat.agent.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

@Component
public class AgentToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolExecutor.class);

    private final AgentToolRegistry toolRegistry;

    public AgentToolExecutor(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行工具调用
     */
    public AgentToolRegistry.ToolExecutionResult executeTool(String toolName,
                                                             Map<String, Object> arguments,
                                                             String userId,
                                                             Consumer<String> chunkConsumer) {
        logger.info("执行Agent Tool: name={}, userId={}, args={}", toolName, userId, arguments);

        try {
            return toolRegistry.executeTool(toolName, arguments, userId, chunkConsumer);
        } catch (Exception e) {
            logger.warn("Agent Tool执行失败: name={}", toolName, e);
            throw new RuntimeException("工具 " + toolName + " 执行失败: " + e.getMessage(), e);
        }
    }
}
