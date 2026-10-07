package com.mindflow.harness.execute;

import com.mindflow.harness.plan.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDecisionFlowTest {

    @Test
    void taskNeedsDecisionOnlyWhenOptionsExistAndUnsettled() {
        Task task = new Task("task_1", "实现贪吃蛇渲染", Task.TaskType.FILE_WRITE);
        assertFalse(task.hasPendingDecision(), "无候选项的任务不应触发澄清闸门");

        task.setOptions(List.of(
                new Task.TaskOption("Canvas 2D", "零依赖、性能好"),
                new Task.TaskOption("DOM 网格", "易调试但性能较差")));
        assertTrue(task.hasPendingDecision());

        task.settleDecision("Canvas 2D", null);
        assertFalse(task.hasPendingDecision(), "已拍板的任务不得再次追问");
        assertEquals("Canvas 2D", task.getChosenOption());
    }

    @Test
    void delegateSettlesWithoutConcreteOption() {
        Task task = new Task("task_1", "实现渲染", Task.TaskType.FILE_WRITE);
        task.setOptions(List.of(new Task.TaskOption("A", "a")));

        task.settleDecision(null, null);

        assertFalse(task.hasPendingDecision());
        assertNull(task.getChosenOption(), "「你来定」不应写入具体方案，避免把空值当成约束注入");
    }

    @Test
    void planReviewCarriesSelections() {
        PlanReviewService service = new PlanReviewService();
        ReflectionTestUtils.setField(service, "reviewTimeoutSeconds", 5L);
        service.register("plan_1");

        CompletableFuture.runAsync(() -> service.complete("plan_1", "execute", null,
                Map.of("task_1", "Canvas 2D")));
        PlanReviewService.Decision decision = service.await("plan_1");

        assertEquals(PlanReviewService.Action.EXECUTE, decision.action());
        assertEquals("Canvas 2D", decision.selections().get("task_1"));
    }

    @Test
    void planReviewWithoutSelectionsStillWorks() {
        PlanReviewService service = new PlanReviewService();
        ReflectionTestUtils.setField(service, "reviewTimeoutSeconds", 5L);
        service.register("plan_2");

        CompletableFuture.runAsync(() -> service.complete("plan_2", "cancel", null));
        PlanReviewService.Decision decision = service.await("plan_2");

        assertEquals(PlanReviewService.Action.CANCEL, decision.action());
        assertTrue(decision.selections().isEmpty());
    }

    @Test
    void clarificationAppliesUserSelection() {
        TaskClarificationService service = new TaskClarificationService();
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5L);
        service.register("c_1");

        CompletableFuture.runAsync(() -> service.complete("c_1", "Canvas 2D", "单文件即可"));
        TaskClarificationService.Choice choice = service.await("c_1");

        assertFalse(choice.delegated());
        assertEquals("Canvas 2D", choice.selection());
        assertEquals("单文件即可", choice.note());
    }

    @Test
    void clarificationBlankChoiceMeansDelegate() {
        TaskClarificationService service = new TaskClarificationService();
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5L);
        service.register("c_2");

        CompletableFuture.runAsync(() -> service.complete("c_2", "   ", null));

        assertTrue(service.await("c_2").delegated());
    }

    @Test
    void clarificationTimeoutNeverBlocksTheSession() {
        TaskClarificationService service = new TaskClarificationService();
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);
        service.register("c_3");

        TaskClarificationService.Choice choice = service.await("c_3");

        assertTrue(choice.delegated(), "无人响应时必须交给 Agent 自行决定，不能永久挂起");
    }
}
