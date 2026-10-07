package com.mindflow.harness.execute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 任务失败决策状态机：任务失败后不再直接判死或自动重规划，而是把选择权交给用户
 * （重试 / 重新规划 / 跳过 / 终止）。前端通过 failure_decision_response 恢复；
 * 超时未决策（默认 180s）按 SKIP 收回，避免会话永久挂起。
 */
@Service
public class FailureDecisionService {

    private static final Logger logger = LoggerFactory.getLogger(FailureDecisionService.class);

    public enum Action {
        RETRY,
        REPLAN,
        SKIP,
        ABORT
    }

    @Value("${mindflow.plan.failure-decision-timeout-seconds:180}")
    private long decisionTimeoutSeconds;

    private final Map<String, CompletableFuture<Action>> pending = new ConcurrentHashMap<>();

    public void register(String decisionId) {
        pending.put(decisionId, new CompletableFuture<>());
    }

    public long timeoutSeconds() {
        return Math.max(1, decisionTimeoutSeconds);
    }

    public boolean complete(String decisionId, String action) {
        CompletableFuture<Action> future = pending.get(decisionId);
        if (future == null) {
            logger.warn("收到未注册的失败决策响应: decisionId={}", decisionId);
            return false;
        }
        Action parsed = parseAction(action);
        if (parsed == null) {
            logger.warn("收到无法识别的失败决策: decisionId={}, action={}", decisionId, action);
            return false;
        }
        return future.complete(parsed);
    }

    public Action await(String decisionId) {
        CompletableFuture<Action> future = pending.get(decisionId);
        if (future == null) {
            return Action.SKIP;
        }
        try {
            return future.get(Math.max(1, decisionTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("失败决策超时，默认跳过: decisionId={}, timeout={}s", decisionId, decisionTimeoutSeconds);
            return Action.SKIP;
        } catch (Exception e) {
            logger.warn("等待失败决策失败，默认跳过: decisionId={}, error={}", decisionId, e.getMessage());
            return Action.SKIP;
        } finally {
            pending.remove(decisionId);
        }
    }

    private Action parseAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.trim().toLowerCase()) {
            case "retry" -> Action.RETRY;
            case "replan" -> Action.REPLAN;
            case "skip" -> Action.SKIP;
            case "abort", "cancel", "stop" -> Action.ABORT;
            default -> null;
        };
    }
}
