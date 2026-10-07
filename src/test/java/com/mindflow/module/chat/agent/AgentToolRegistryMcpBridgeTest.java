package com.mindflow.module.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.client.DeepSeekClient;
import com.mindflow.harness.mcp.DefaultMcpToolRegistry;
import com.mindflow.harness.mcp.protocol.McpToolDescriptor;
import com.mindflow.module.document.repository.FileUploadRepository;
import com.mindflow.module.memory.service.LongTermMemoryService;
import com.mindflow.module.search.service.HybridSearchService;
import com.mindflow.module.search.service.RerankService;
import com.mindflow.module.wiki.service.WikiSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolRegistryMcpBridgeTest {

    private AgentToolRegistry registry;
    private DefaultMcpToolRegistry mcpRegistry;

    @BeforeEach
    void setUp() {
        mcpRegistry = new DefaultMcpToolRegistry();
        registry = new AgentToolRegistry(
                mock(HybridSearchService.class),
                mock(DeepSeekClient.class),
                mock(StringRedisTemplate.class),
                mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                mock(FileUploadRepository.class),
                mock(RerankService.class),
                mock(WikiSearchService.class),
                mock(LongTermMemoryService.class),
                mcpRegistry,
                mock(com.mindflow.harness.skill.SkillRegistry.class),
                mock(com.mindflow.harness.skill.SkillContextBuffer.class),
                mock(com.mindflow.module.skill.service.SkillPackageService.class),
                new LocalFileTools(new com.mindflow.harness.policy.ToolPolicyGateway(
                        true, true, System.getProperty("user.dir"), null, null)),
                mock(com.mindflow.module.chat.repository.MessageFeedbackRepository.class),
                new ObjectMapper());
    }

    @Test
    void mcpToolsMergeIntoToolDefinitionsAndRoute() {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "demo", "click", "mcp__demo__click", "点击元素",
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())));
        mcpRegistry.registerMcpTool(descriptor, argsJson -> "clicked: " + argsJson);

        List<AgentToolRegistry.AgentTool> tools = registry.getTools();
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("mcp__demo__click")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("search_knowledge")));

        AgentToolRegistry.ToolExecutionResult result = registry.executeTool(
                "mcp__demo__click", Map.of("selector", "#btn"), "u1", null);
        assertTrue(result.success());
        assertTrue(result.content().contains("clicked"));
        assertEquals("mcp__demo__click", result.toolName());
    }

    @Test
    void unregisteredNameStillFailsFast() {
        boolean threw = false;
        try {
            registry.executeTool("mcp__ghost__tool", Map.of(), "u1", null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw);
    }
}
