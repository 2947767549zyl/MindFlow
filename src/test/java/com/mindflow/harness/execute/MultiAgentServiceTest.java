package com.mindflow.harness.execute;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentServiceTest {

    private final MultiAgentService service = new MultiAgentService(null, null, null);

    @Test
    void shouldParseSimplePlan() {
        String planJson = """
                {
                    "summary": "读取文件",
                    "steps": [
                        {
                            "id": "step_1",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<MultiAgentService.ExecutionStep> steps = service.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("读取 pom.xml", steps.get(0).description());
    }

    @Test
    void shouldParseMultiStepPlanWithDependencies() {
        String planJson = """
                {
                    "summary": "创建并验证项目",
                    "steps": [
                        {"id": "s1", "description": "创建项目", "type": "COMMAND", "dependencies": []},
                        {"id": "s2", "description": "读取 pom.xml", "type": "FILE_READ", "dependencies": ["s1"]},
                        {"id": "s3", "description": "验证结构", "type": "VERIFICATION", "dependencies": ["s2"]}
                    ]
                }
                """;

        List<MultiAgentService.ExecutionStep> steps = service.parsePlan(planJson);
        assertEquals(3, steps.size());

        assertEquals("step_1", steps.get(0).id());
        assertEquals("step_2", steps.get(1).id());
        assertEquals("step_3", steps.get(2).id());

        assertTrue(steps.get(0).dependencies().isEmpty());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldParsePlanWithMarkdownCodeBlock() {
        String planJson = """
                ```json
                {
                    "summary": "简单任务",
                    "steps": [
                        {"id": "t1", "description": "执行命令", "type": "COMMAND", "dependencies": []}
                    ]
                }
                ```
                """;

        List<MultiAgentService.ExecutionStep> steps = service.parsePlan(planJson);
        assertEquals(1, steps.size());
    }

    @Test
    void shouldParsePlanWithTasksField() {
        String planJson = """
                {
                    "summary": "用 tasks 字段",
                    "tasks": [
                        {"id": "task_1", "description": "第一步", "type": "COMMAND", "dependencies": []}
                    ]
                }
                """;

        List<MultiAgentService.ExecutionStep> steps = service.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).description());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        assertTrue(service.parsePlan("").isEmpty());
        assertTrue(service.parsePlan("not json").isEmpty());
        assertTrue(service.parsePlan("{}").isEmpty());
        assertTrue(service.parsePlan("{\"steps\": []}").isEmpty());
    }

    @Test
    void shouldGetExecutableSteps() {
        List<MultiAgentService.ExecutionStep> steps = new ArrayList<>(List.of(
                MultiAgentService.ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                MultiAgentService.ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        List<MultiAgentService.ExecutionStep> executable = service.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_1", executable.get(0).id());

        steps.set(0, steps.get(0).withResult("项目已创建"));
        executable = service.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_2", executable.get(0).id());
    }

    @Test
    void shouldGetMultipleExecutableStepsForParallelTasks() {
        List<MultiAgentService.ExecutionStep> steps = List.of(
                MultiAgentService.ExecutionStep.pending("step_1", "任务A", "COMMAND", List.of()),
                MultiAgentService.ExecutionStep.pending("step_2", "任务B", "COMMAND", List.of()),
                MultiAgentService.ExecutionStep.pending("step_3", "汇总", "ANALYSIS", List.of("step_1", "step_2"))
        );

        List<MultiAgentService.ExecutionStep> executable = service.getExecutableSteps(steps);
        assertEquals(2, executable.size());
    }

    @Test
    void shouldParseReviewApproval() {
        assertTrue(service.parseReviewApproval(
                "{\"approved\": true, \"summary\": \"通过\", \"issues\": []}"));
        assertFalse(service.parseReviewApproval(
                "{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺少错误处理\"]}"));
        assertFalse(service.parseReviewApproval(null));
        assertFalse(service.parseReviewApproval(""));
        assertFalse(service.parseReviewApproval("执行结果未通过审查"));
        assertFalse(service.parseReviewApproval("代码质量不合格"));
        assertTrue(service.parseReviewApproval("审查通过，代码质量良好"));
        assertFalse(service.parseReviewApproval("hmm"));
        assertFalse(service.parseReviewApproval("{\"summary\": \"无 approved 字段\"}"));
    }

    @Test
    void shouldParseReviewIssues() {
        String reviewJson = """
                {
                    "approved": false,
                    "summary": "存在问题",
                    "issues": ["缺少错误处理", "代码风格不一致"],
                    "suggestions": ["添加 try-catch", "统一缩进"]
                }
                """;

        String issues = service.parseReviewIssues(reviewJson);
        assertTrue(issues.contains("缺少错误处理"));
        assertTrue(issues.contains("代码风格不一致"));
    }

    @Test
    void shouldFallbackToSummaryForIssues() {
        String issues = service.parseReviewIssues(
                "{\"approved\": false, \"summary\": \"质量不达标\", \"issues\": []}");
        assertEquals("质量不达标", issues);
    }

    @Test
    void shouldHandleInvalidReviewJson() {
        String issues = service.parseReviewIssues("not valid json");
        assertEquals("审查未通过，请改进执行结果", issues);
    }
}
