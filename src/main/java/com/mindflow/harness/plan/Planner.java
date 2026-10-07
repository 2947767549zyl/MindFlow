package com.mindflow.harness.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划器 - 使用 LLM 将复杂任务分解为执行计划（移植自 MindFlow plan 包，LLM 出口改为 LlmProviderRouter）
 */
public class Planner {

    private static final Logger logger = LoggerFactory.getLogger(Planner.class);
    private static final int PLANNING_MAX_COMPLETION_TOKENS = 2000;

    private static final String PLANNING_PROMPT = """
            你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。

            可用任务类型：
            - ANALYSIS: 分析结果并做出决策
            - VERIFICATION: 验证结果是否正确
            - COMMAND: 执行操作
            - FILE_READ: 读取资料
            - FILE_WRITE: 产出内容

            请按以下JSON格式输出执行计划：
            {
                "summary": "任务摘要",
                "tasks": [
                    {
                        "id": "task_1",
                        "description": "任务描述",
                        "type": "ANALYSIS",
                        "dependencies": []
                    }
                ]
            }

            规则：
            1. 每个任务必须有唯一的id（如 task_1, task_2）
            2. dependencies列出依赖的任务id
            3. 任务应该按执行顺序排列
            4. 任务描述要具体明确
            5. 简单任务允许只生成1-3个任务；不要为了凑步数引入无关步骤
            6. 复杂任务再拆分为5-10个子任务
            7. 不要为了"保存中间结果"而额外创建 FILE_WRITE / FILE_READ，除非用户明确要求落盘
            8. 如果一个任务一步就能完成，就保持最短计划

            需要用户拍板的任务（重要）：
            9. 如果某个任务在动手前存在多个"等价合理"的选择（技术栈、渲染方式、文件结构、代码风格、
               范围边界等），请为该任务额外给出 options 数组：2-3 个候选项，第一个是你的推荐，
               每项用一句 reason 说明取舍；用户会在审阅计划时选定。
            10. 没有歧义的任务不要给 options（避免计划啰嗦）；最多 3 个任务带 options。

            写文件类任务的粒度（重要）：
            11. 涉及写文件的任务，**一个任务最多产出一个文件**；需要多个文件时必须拆成多个任务，
                不要把 8 个文件塞进一个任务（单次输出长度装不下，会导致该任务整体失败）。
            12. 单个文件预计超过 300 行时，拆成两个任务：先"写骨架（类与关键方法签名）"，再"分块补齐方法体"。

            只输出JSON，不要有其他内容。
            """;

    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper mapper;

    public Planner(LlmProviderRouter llmProviderRouter, ObjectMapper mapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public ExecutionPlan createPlan(String requesterId, String goal) {
        return createPlan(requesterId, goal, "");
    }

    /**
     * 带会话历史的规划：historyContext 会作为一条独立的 user 消息注入，
     * 让规划器知道"上一轮聊过什么"，避免把新请求当成孤立任务。
     */
    public ExecutionPlan createPlan(String requesterId, String goal, String historyContext) {
        boolean hasHistory = historyContext != null && !historyContext.isBlank();
        logger.info("创建执行计划: goal={}, hasHistory={}", goal, hasHistory);
        if (!hasHistory && isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage(PLANNING_PROMPT));
        if (hasHistory) {
            messages.add(userMessage(historyContext));
        }
        messages.add(userMessage("请为以下任务制定执行计划：\n" + goal));

        var turn = llmProviderRouter.completeReActTurn(
                requesterId, messages, null, PLANNING_MAX_COMPLETION_TOKENS);
        ExecutionPlan plan = parsePlan(goal, turn.content());
        plan.setContext(historyContext);
        return plan;
    }

    public ExecutionPlan replan(String requesterId, ExecutionPlan failedPlan, String failureReason) {
        logger.info("重新规划: goal={}, reason={}", failedPlan.getGoal(), failureReason);

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(requesterId, context.toString(), failedPlan.getContext());
    }

    private ExecutionPlan parsePlan(String goal, String planJson) {
        String cleaned = planJson == null ? "" : planJson
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        try {
            JsonNode root = mapper.readTree(cleaned);
            String summary = root.path("summary").asText();
            JsonNode tasksNode = root.path("tasks");
            plan.setSummary(summary);

            if (!tasksNode.isArray() || tasksNode.isEmpty()) {
                plan.addTask(new Task("task_1", goal, Task.TaskType.ANALYSIS));
                plan.computeExecutionOrder();
                return plan;
            }

            Map<String, String> idMapping = new HashMap<>();
            int taskIndex = 1;

            for (JsonNode taskNode : tasksNode) {
                String originalId = taskNode.path("id").asText();
                String newId = "task_" + taskIndex++;
                idMapping.put(originalId, newId);

                String description = taskNode.path("description").asText();
                String typeStr = taskNode.path("type").asText();
                Task.TaskType type = parseTaskType(typeStr);

                Task task = new Task(newId, description, type);
                task.setOptions(parseOptions(taskNode.path("options")));
                plan.addTask(task);
            }

            taskIndex = 1;
            for (JsonNode taskNode : tasksNode) {
                String newId = "task_" + taskIndex++;
                Task task = plan.getTask(newId);

                JsonNode depsNode = taskNode.path("dependencies");
                if (depsNode.isArray()) {
                    for (JsonNode depNode : depsNode) {
                        String originalDepId = depNode.asText();
                        String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                        Task dep = plan.getTask(newDepId);
                        if (dep != null) {
                            task.addDependency(newDepId);
                            dep.addDependent(task.getId());
                        }
                    }
                }
            }

            if (!plan.computeExecutionOrder()) {
                throw new IllegalStateException("计划中存在循环依赖");
            }
            return plan;
        } catch (Exception e) {
            logger.warn("解析计划 JSON 失败，回退为最小计划: {}", e.getMessage());
            ExecutionPlan fallback = new ExecutionPlan(generatePlanId(), goal);
            fallback.setSummary("解析失败，直接执行原任务");
            fallback.addTask(new Task("task_1", goal, Task.TaskType.ANALYSIS));
            fallback.computeExecutionOrder();
            return fallback;
        }
    }

    /** 最多保留 3 个候选项：既约束模型啰嗦，也与前端预选"第一项为推荐"的约定对齐 */
    private List<Task.TaskOption> parseOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.isEmpty()) {
            return List.of();
        }
        List<Task.TaskOption> options = new ArrayList<>();
        for (JsonNode optionNode : optionsNode) {
            String label = optionNode.path("label").asText("").trim();
            if (label.isEmpty()) {
                continue;
            }
            options.add(new Task.TaskOption(label, optionNode.path("reason").asText("").trim()));
            if (options.size() >= 3) {
                break;
            }
        }
        return options;
    }

    private Task.TaskType parseTaskType(String typeStr) {
        if (typeStr == null) {
            return Task.TaskType.ANALYSIS;
        }
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            case "PLANNING" -> Task.TaskType.PLANNING;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private Map<String, Object> systemMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private Map<String, Object> userMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }
}
