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
 * 计划审阅状态机：将 MindFlow CLI 的"Enter/ESC/I"单键阻塞审阅改造为 Web 异步挂起。
 * 每次规划完成后注册一个 planId 的待决 Future，前端通过 plan_review_response 恢复；
 * 超时未决策（默认 300s）按 CANCEL 收回，避免会话永久挂起。
 */
@Service
public class PlanReviewService {

    private static final Logger logger = LoggerFactory.getLogger(PlanReviewService.class);

    public enum Action {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record Decision(Action action, String feedback, Map<String, String> selections) {
        public static Decision execute() {
            return new Decision(Action.EXECUTE, null, Map.of());
        }

        public static Decision cancel() {
            return new Decision(Action.CANCEL, null, Map.of());
        }

        public static Decision supplement(String feedback) {
            return new Decision(Action.SUPPLEMENT, feedback, Map.of());
        }
    }

    @Value("${mindflow.plan.review-timeout-seconds:300}")
    private long reviewTimeoutSeconds;

    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    public void register(String planId) {
        pending.put(planId, new CompletableFuture<>());
    }

    public long reviewTimeoutMillis() {
        return Math.max(1, reviewTimeoutSeconds) * 1000L;
    }

    public boolean complete(String planId, String action, String feedback) {
        return complete(planId, action, feedback, Map.of());
    }

    public boolean complete(String planId, String action, String feedback, Map<String, String> selections) {
        CompletableFuture<Decision> future = pending.get(planId);
        if (future == null) {
            logger.warn("收到未注册的计划审阅响应: planId={}", planId);
            return false;
        }
        Action parsed = parseAction(action);
        if (parsed == null) {
            return false;
        }
        Map<String, String> safeSelections = selections == null ? Map.of() : Map.copyOf(selections);
        if (parsed == Action.SUPPLEMENT) {
            return future.complete(new Decision(Action.SUPPLEMENT, feedback, safeSelections));
        }
        if (parsed == Action.CANCEL) {
            return future.complete(new Decision(Action.CANCEL, null, safeSelections));
        }
        return future.complete(new Decision(Action.EXECUTE, null, safeSelections));
    }

    public Decision await(String planId) {
        CompletableFuture<Decision> future = pending.get(planId);
        if (future == null) {
            return Decision.cancel();
        }
        try {
            return future.get(Math.max(1, reviewTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("计划审阅超时，自动取消: planId={}, timeout={}s", planId, reviewTimeoutSeconds);
            return Decision.cancel();
        } catch (Exception e) {
            logger.warn("等待计划审阅决策失败: planId={}, error={}", planId, e.getMessage());
            return Decision.cancel();
        } finally {
            pending.remove(planId);
        }
    }

    private Action parseAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.trim().toLowerCase()) {
            case "execute", "approve" -> Action.EXECUTE;
            case "cancel", "reject" -> Action.CANCEL;
            case "supplement" -> Action.SUPPLEMENT;
            default -> null;
        };
    }
}
