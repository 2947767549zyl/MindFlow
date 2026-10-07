package com.mindflow.harness.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindflow.common.exception.RateLimitExceededException;
import com.mindflow.harness.plan.ExecutionPlan;
import com.mindflow.harness.plan.Planner;
import com.mindflow.harness.plan.Task;
import com.mindflow.module.chat.agent.AgentToolRegistry;
import com.mindflow.module.chat.handler.ChatStreamingService;
import com.mindflow.runtime.CancellationContext;
import com.mindflow.structure.ai.LlmProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 服务（移植自 MindFlow agent.PlanExecuteAgent，Web 化：
 * 终端单键审阅 → PlanReviewService 异步挂起，System.out → ChatStreamingService WS 推送）。
 */
@Service
public class PlanExecuteService {

    private static final Logger logger = LoggerFactory.getLogger(PlanExecuteService.class);
    @Value("${mindflow.plan.task-max-completion-tokens:8000}")
    private int executionMaxCompletionTokens;
    private static final int MAX_BUDGET_RETRIES = 3;
    private static final int MAX_DECISION_RETRIES = 2;
    private static final long MAX_BUDGET_WAIT_SECONDS = 90;

    private static final String EXECUTION_PROMPT = """
            你是一个任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

            当前任务类型：%s
            任务描述：%s

            可用工具由系统提供，参数以工具 schema 为准。需要内部知识时优先 search_knowledge；
            需要整理总结时先 search_knowledge 再 generate_summary；仅统计类问题才用 knowledge_stats；
            用户明确说"记一下/记住/以后记得"时用 save_memory 保存精炼稳定事实。
            同一轮返回多个工具调用时系统会并行执行；工具之间有依赖时请分多轮调用。
            如果是 ANALYSIS 或 VERIFICATION 类型任务，请基于上下文直接给出结果，不要无意义地调用工具。

            大文件写入策略（重要）：
            1. 单次工具调用装不下大文件——文件预计超过 300 行时，先 write_file 写骨架（类声明与关键方法签名），
               再用 edit_file 分多轮把方法体补齐，不要在一个调用里硬塞全文。
            2. 若本轮任务要求多个文件，按优先级先完成最核心的那个，并在结果里说明剩余文件需由后续任务处理。

            请用中文回复。
            """;

    private final LlmProviderRouter llmProviderRouter;
    private final AgentToolRegistry toolRegistry;
    private final ParallelToolRunner parallelToolRunner;
    private final ChatStreamingService streamingService;
    private final PlanReviewService reviewService;
    private final FailureDecisionService failureDecisionService;
    private final TaskClarificationService taskClarificationService;
    private final com.mindflow.harness.skill.SkillRegistry skillRegistry;
    private final com.mindflow.harness.skill.SkillContextBuffer skillContextBuffer;
    private final com.mindflow.harness.memory.ConversationHistoryCompactor historyCompactor;
    private final ObjectMapper objectMapper;
    private final Planner planner;

    @Value("${mindflow.react.max-rounds:10}")
    private int maxRounds;

    public PlanExecuteService(LlmProviderRouter llmProviderRouter,
                              AgentToolRegistry toolRegistry,
                              ParallelToolRunner parallelToolRunner,
                              ChatStreamingService streamingService,
                              PlanReviewService reviewService,
                              FailureDecisionService failureDecisionService,
                              TaskClarificationService taskClarificationService,
                              com.mindflow.harness.skill.SkillRegistry skillRegistry,
                              com.mindflow.harness.skill.SkillContextBuffer skillContextBuffer,
                              ObjectMapper objectMapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.parallelToolRunner = parallelToolRunner;
        this.streamingService = streamingService;
        this.reviewService = reviewService;
        this.failureDecisionService = failureDecisionService;
        this.taskClarificationService = taskClarificationService;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.historyCompactor = new com.mindflow.harness.memory.ConversationHistoryCompactor(llmProviderRouter);
        this.objectMapper = objectMapper;
        this.planner = new Planner(llmProviderRouter, objectMapper);
    }

    public String run(String userId, String generationId, String conversationId, String goal,
                      java.util.List<java.util.Map<String, String>> history) {
        logger.info("Plan 执行开始: generationId={}, goal={}, historyTurns={}",
                generationId, goal, history == null ? 0 : history.size());
        try {
            ExecutionPlan plan = planner.createPlan(userId, goal,
                    com.mindflow.harness.memory.HistoryDigest.render(history));
            return reviewAndExecute(userId, generationId, conversationId, plan);
        } catch (Exception e) {
            logger.error("Plan 执行失败: generationId={}", generationId, e);
            return "❌ 计划执行失败: " + e.getMessage();
        }
    }

    private String reviewAndExecute(String userId, String generationId,
                                    String conversationId, ExecutionPlan plan) {
        while (true) {
            if (isCancelled(generationId)) {
                streamingService.sendPlanStatus(userId, generationId, conversationId,
                        plan.getId(), "cancelled", plan.getProgress());
                return "⏹️ 已取消本次计划执行。";
            }

            reviewService.register(plan.getId());
            streamingService.sendPlanReview(userId, generationId, conversationId,
                    plan.getId(), plan.getGoal(), plan.toStepDtos(),
                    System.currentTimeMillis() + reviewService.reviewTimeoutMillis());

            PlanReviewService.Decision decision = reviewService.await(plan.getId());
            if (decision.action() == PlanReviewService.Action.CANCEL) {
                streamingService.sendPlanStatus(userId, generationId, conversationId,
                        plan.getId(), "cancelled", plan.getProgress());
                return "⏹️ 已取消本次计划执行。";
            }

            if (decision.action() == PlanReviewService.Action.SUPPLEMENT) {
                String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
                if (!feedback.isEmpty()) {
                    plan = planner.createPlan(userId, plan.getGoal() + "\n补充要求：" + feedback);
                    continue;
                }
            }

            applySelections(plan, decision.selections());
            return executePlan(userId, generationId, conversationId, plan);
        }
    }

    private String executePlan(String userId, String generationId,
                               String conversationId, ExecutionPlan plan) {
        plan.markStarted();
        streamingService.sendPlanStatus(userId, generationId, conversationId,
                plan.getId(), "executing", plan.getProgress());

        StringBuilder finalResult = new StringBuilder();
        Map<String, String> taskResults = new LinkedHashMap<>();
        Set<String> budgetBlockedTasks = ConcurrentHashMap.newKeySet();
        Map<String, Integer> decisionRetries = new ConcurrentHashMap<>();

        while (true) {
            if (isCancelled(generationId)) {
                streamingService.sendPlanStatus(userId, generationId, conversationId,
                        plan.getId(), "cancelled", plan.getProgress());
                return "⏹️ 已取消本次计划执行。";
            }

            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<Task> batch = executeTaskBatch(userId, generationId, conversationId, plan, executableTasks, budgetBlockedTasks);
            List<Task> failedTasks = new ArrayList<>();
            for (Task task : batch) {
                if (task == null) {
                    continue;
                }
                if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                    taskResults.put(task.getId(), task.getResult());
                    streamingService.sendPlanStep(userId, generationId, conversationId,
                            plan.getId(), task.getId(), "completed", task.getDescription(), task.getResult());
                    continue;
                }
                streamingService.sendPlanStep(userId, generationId, conversationId,
                        plan.getId(), task.getId(), "failed", task.getDescription(), task.getError());
                failedTasks.add(task);
            }

            if (failedTasks.isEmpty()) {
                continue;
            }

            List<Task> retryableTasks = failedTasks.stream()
                    .filter(task -> decisionRetries.getOrDefault(task.getId(), 0) < MAX_DECISION_RETRIES)
                    .toList();
            FailureDecisionService.Action decision = requestFailureDecision(
                    userId, generationId, conversationId, failedTasks, retryableTasks, budgetBlockedTasks);

            if (decision == FailureDecisionService.Action.ABORT) {
                streamingService.sendPlanStatus(userId, generationId, conversationId,
                        plan.getId(), "cancelled", plan.getProgress());
                return "⏹️ 已按你的选择终止本次计划执行。";
            }

            if (decision == FailureDecisionService.Action.REPLAN) {
                streamingService.sendNotice(userId, generationId, conversationId, "warning", "plan",
                        "计划已重新规划", "按你的选择，正在基于失败信息重新规划剩余任务。", false);
                ExecutionPlan replanned = planner.replan(userId, plan, describeFailures(failedTasks));
                return reviewAndExecute(userId, generationId, conversationId, replanned);
            }

            if (decision == FailureDecisionService.Action.RETRY) {
                for (Task task : retryableTasks) {
                    decisionRetries.merge(task.getId(), 1, Integer::sum);
                    task.markPending();
                }
                logger.info("按用户选择重试 {} 个失败任务", retryableTasks.size());
                continue;
            }

            for (Task task : failedTasks) {
                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(task.getError());
                markDependentsSkipped(plan, task);
            }
        }

        streamingService.sendPlanStatus(userId, generationId, conversationId,
                plan.getId(), plan.hasFailed() ? "failed" : "completed", plan.getProgress());

        String planSummary = finalResult.isEmpty() ? buildFinalResult(plan) : finalResult.toString();
        if (plan.hasFailed()) {
            plan.markFailed();
            return planSummary.isBlank() ? "⚠️ 计划部分完成，有任务失败。" : "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }
        plan.markCompleted();
        return planSummary.isBlank() ? "✅ 计划执行完成！" : "✅ 计划执行完成！\n" + planSummary;
    }

    private FailureDecisionService.Action requestFailureDecision(String userId, String generationId,
                                                                 String conversationId, List<Task> failedTasks,
                                                                 List<Task> retryableTasks,
                                                                 Set<String> budgetBlockedTasks) {
        if (retryableTasks.isEmpty()) {
            for (Task task : failedTasks) {
                boolean budgetBlocked = budgetBlockedTasks.contains(task.getId());
                streamingService.sendNotice(userId, generationId, conversationId, "error", "plan_task",
                        budgetBlocked ? "任务失败：LLM 预算受限" : "任务失败",
                        "任务 " + task.getId() + "（" + task.getDescription() + "）失败：" + task.getError()
                                + "；已达到重试上限，结果不计入最终答案。",
                        budgetBlocked);
            }
            return FailureDecisionService.Action.SKIP;
        }

        List<Map<String, Object>> taskDtos = new ArrayList<>();
        for (Task task : retryableTasks) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", task.getId());
            dto.put("description", task.getDescription());
            dto.put("error", task.getError());
            taskDtos.add(dto);
        }

        String decisionId = generationId + "-failure-" + System.nanoTime();
        failureDecisionService.register(decisionId);
        streamingService.sendFailureDecision(userId, generationId, conversationId, decisionId,
                taskDtos, failureDecisionService.timeoutSeconds());
        logger.info("已请求失败决策: decisionId={}, tasks={}, timeout={}s",
                decisionId, taskDtos.size(), failureDecisionService.timeoutSeconds());
        return failureDecisionService.await(decisionId);
    }

    private String describeFailures(List<Task> failedTasks) {
        StringBuilder description = new StringBuilder();
        for (Task task : failedTasks) {
            if (!description.isEmpty()) {
                description.append("；");
            }
            description.append("任务 ").append(task.getId()).append(" 失败: ").append(task.getError());
        }
        return description.isEmpty() ? "任务失败" : description.toString();
    }

    private void markDependentsSkipped(ExecutionPlan plan, Task failedTask) {
        for (String dependentId : failedTask.getDependents()) {
            Task dependent = plan.getTask(dependentId);
            if (dependent == null || dependent.getStatus() != Task.TaskStatus.PENDING) {
                continue;
            }
            dependent.markSkipped();
            markDependentsSkipped(plan, dependent);
        }
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<Task> executeTaskBatch(String userId, String generationId, String conversationId,
                                        ExecutionPlan plan, List<Task> executableTasks,
                                        Set<String> budgetBlockedTasks) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            task.markStarted();
            streamingService.sendPlanStep(userId, generationId, conversationId,
                    plan.getId(), task.getId(), "started", task.getDescription(), null);
            runSingleTaskSafe(userId, generationId, conversationId, plan, task, false, budgetBlockedTasks);
            return List.of(task);
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "mindflow-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                task.markStarted();
                streamingService.sendPlanStep(userId, generationId, conversationId,
                        plan.getId(), task.getId(), "started", task.getDescription(), null);
                futures.add(executor.submit(() ->
                        runSingleTaskSafe(userId, generationId, conversationId, plan, task, false, budgetBlockedTasks)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    logger.warn("并行任务等待异常: {}", e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return executableTasks;
    }

    private void runSingleTaskSafe(String userId, String generationId, String conversationId,
                                   ExecutionPlan plan, Task task, boolean streamContent,
                                   Set<String> budgetBlockedTasks) {
        requestClarificationIfNeeded(userId, generationId, conversationId, task);
        for (int attempt = 0; ; attempt++) {
            try {
                String result = executeTask(userId, generationId, conversationId, plan, task, streamContent);
                task.markCompleted(result);
                return;
            } catch (Exception e) {
                long retryAfterSeconds = budgetRetryAfterSeconds(e);
                if (retryAfterSeconds > 0 && attempt < MAX_BUDGET_RETRIES) {
                    long waitSeconds = Math.max(1, Math.min(retryAfterSeconds, MAX_BUDGET_WAIT_SECONDS));
                    logger.warn("任务 {} 命中 LLM 预算限制，等待 {}s 后重试（第 {}/{} 次）: {}",
                            task.getId(), waitSeconds, attempt + 1, MAX_BUDGET_RETRIES, deepestMessage(e));
                    if (!waitBeforeRetry(generationId, waitSeconds)) {
                        task.markFailed("已取消");
                        return;
                    }
                    continue;
                }
                if (isTransientLlmFailure(e) && attempt < MAX_TRANSIENT_RETRIES) {
                    logger.warn("任务 {} 遇到瞬时失败（超时/连接），立即重试（第 {}/{} 次）: {}",
                            task.getId(), attempt + 1, MAX_TRANSIENT_RETRIES, deepestMessage(e));
                    continue;
                }
                String reason = deepestMessage(e);
                logger.warn("任务执行失败: taskId={}, error={}", task.getId(), reason);
                if (retryAfterSeconds > 0) {
                    budgetBlockedTasks.add(task.getId());
                }
                task.markFailed(reason);
                return;
            }
        }
    }

    private boolean waitBeforeRetry(String generationId, long seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled(generationId)) {
                return false;
            }
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            try {
                Thread.sleep(Math.min(1000L, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled(generationId);
    }

    /**
     * 限流异常可能被 LlmProviderRouter 包成 RuntimeException("ReAct 模型回合调用失败", 原异常)
     * （预算预留发生在 try 之前时则是裸异常），必须沿 cause 链找，否则拿不到 retryAfterSeconds。
     */
    private static final int MAX_TRANSIENT_RETRIES = 2;

    /**
     * 沿 cause 链取最具体的错误信息：LlmProviderRouter 会把真实原因包成
     * RuntimeException("ReAct 模型回合调用失败")，只读最外层会让"截断/超时/400"全部丢失。
     */
    private static String deepestMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? "未知错误" : message;
    }

    /** 瞬时失败（超时 / 连接中断）值得立即重试；截断类失败重试无意义，不做特判以免浪费 token */
    private static boolean isTransientLlmFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("timeout") || lower.contains("timed out")
                        || lower.contains("connection reset") || lower.contains("连接")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long budgetRetryAfterSeconds(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RateLimitExceededException rateLimitExceededException) {
                return Math.max(rateLimitExceededException.getRetryAfterSeconds(), 1L);
            }
            current = current.getCause();
        }
        return -1L;
    }

    private String executeTask(String userId, String generationId, String conversationId,
                               ExecutionPlan plan, Task task, boolean streamContent) {
        String prompt = String.format(EXECUTION_PROMPT, task.getType(), task.getDescription());
        String skillIndex = com.mindflow.harness.skill.SkillIndexFormatter.format(skillRegistry.enabledSkills());
        if (!skillIndex.isEmpty()) {
            prompt = prompt + "\n" + skillIndex;
        }
        String taskInput = buildTaskContext(plan, task);
        String skillBodies = skillContextBuffer.drain(userId);
        if (!skillBodies.isEmpty()) {
            taskInput = skillBodies + "\n\n" + taskInput;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", prompt));
        messages.add(message("user", taskInput));

        int compactTriggerTokens = llmProviderRouter.contextCompactTriggerTokens();
        StringBuilder allResults = new StringBuilder();
        int taskMaxIterations = Math.max(1, maxRounds / 2);
        int iteration = 0;

        while (iteration < taskMaxIterations) {
            if (isCancelled(generationId)) {
                return "⏹️ 已取消任务 [" + task.getId() + "]。";
            }
            iteration++;

            historyCompactor.compactIfNeeded(messages, compactTriggerTokens);

            var turn = llmProviderRouter.completeReActTurn(
                    userId, messages, toolRegistry.getTools(), executionMaxCompletionTokens);

            if (turn.toolCalls().isEmpty()) {
                String content = turn.content() == null ? "" : turn.content();
                if (content.isBlank() && !allResults.isEmpty()) {
                    content = allResults.toString().trim();
                }
                if (streamContent && !content.isBlank()) {
                    streamingService.appendStreamChunk(userId, generationId, conversationId, content);
                }
                return content;
            }

            messages.add(turn.assistantMessage());
            List<ParallelToolRunner.ToolOutcome> outcomes = parallelToolRunner.executeBatch(
                    new ToolContext(userId, conversationId, generationId), turn.toolCalls(),
                    chunk -> streamingService.appendStreamChunk(userId, generationId, conversationId, chunk));
            for (var outcome : outcomes) {
                allResults.append(outcome.content()).append("\n");
                messages.add(toolMessage(outcome.toolCallId(), outcome.content()));
            }
        }

        String fallback = allResults.toString().trim();
        if (streamContent && !fallback.isBlank()) {
            streamingService.appendStreamChunk(userId, generationId, conversationId, fallback);
        }
        return fallback;
    }

    private void applySelections(ExecutionPlan plan, java.util.Map<String, String> selections) {
        if (selections == null || selections.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, String> entry : selections.entrySet()) {
            Task task = plan.getTask(entry.getKey());
            if (task == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            task.settleDecision(entry.getValue(), null);
        }
        logger.info("已应用用户在计划审阅中的方案选择: {}", selections);
    }

    /** 任务级澄清闸门：计划里有候选项但用户没选定，就在动手前问一次（问完即落定，不会追问第二次） */
    private void requestClarificationIfNeeded(String userId, String generationId, String conversationId, Task task) {
        if (!task.hasPendingDecision()) {
            return;
        }
        String clarificationId = generationId + "-clarify-" + task.getId();
        taskClarificationService.register(clarificationId);
        streamingService.sendTaskClarification(userId, generationId, conversationId, clarificationId,
                task.getId(), task.getDescription(), optionDtos(task), taskClarificationService.timeoutSeconds());
        logger.info("已请求任务澄清: task={}, options={}", task.getId(), task.getOptions().size());
        TaskClarificationService.Choice choice = taskClarificationService.await(clarificationId);
        if (choice.delegated()) {
            task.settleDecision(null, null);
        } else {
            task.settleDecision(choice.selection(), choice.note());
        }
    }

    private java.util.List<java.util.Map<String, Object>> optionDtos(Task task) {
        java.util.List<java.util.Map<String, Object>> dtos = new java.util.ArrayList<>();
        for (Task.TaskOption option : task.getOptions()) {
            java.util.Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("label", option.label());
            dto.put("reason", option.reason());
            dtos.add(dto);
        }
        return dtos;
    }

    private String buildTaskContext(ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        if (task.getChosenOption() != null && !task.getChosenOption().isBlank()) {
            context.append("已确认方案：").append(task.getChosenOption())
                    .append("（用户已拍板，请严格按此方案实现，不要自行更换）\n");
        }
        if (task.getChosenNote() != null && !task.getChosenNote().isBlank()) {
            context.append("用户补充要求：").append(task.getChosenNote()).append("\n");
        }
        if (plan.getContext() != null && !plan.getContext().isBlank()) {
            context.append(plan.getContext()).append("\n\n");
        }
        context.append("总目标：").append(plan.getGoal()).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是 ANALYSIS 或 VERIFICATION 类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }
        return result.toString();
    }

    private boolean isCancelled(String generationId) {
        return streamingService.isGenerationCancelled(generationId)
                || CancellationContext.isCancelled(generationId);
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content);
        return message;
    }
}
