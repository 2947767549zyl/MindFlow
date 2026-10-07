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
 * 任务级澄清状态机：任务动手前需要用户拍板（技术栈 / 方案 / 范围）时挂起等待，前端回传后恢复。
 *
 * 超时或异常一律按"交给 Agent 自行决定"处理（不阻塞用户，也不让会话永久挂起）——
 * 这与 HITL 审批"超时拒绝"的取向不同：审批涉及副作用要保守，澄清只是缺输入，默认放行更合理。
 */
@Service
public class TaskClarificationService {

    private static final Logger logger = LoggerFactory.getLogger(TaskClarificationService.class);

    /** delegated=true 表示用户选择"你来定"，或超时未答 → Agent 自行决定 */
    public record Choice(String selection, String note, boolean delegated) {
    }

    @Value("${mindflow.plan.clarification-timeout-seconds:180}")
    private long timeoutSeconds;

    private final Map<String, CompletableFuture<Choice>> pending = new ConcurrentHashMap<>();

    public void register(String clarificationId) {
        pending.put(clarificationId, new CompletableFuture<>());
    }

    public long timeoutSeconds() {
        return Math.max(1, timeoutSeconds);
    }

    public boolean complete(String clarificationId, String selection, String note) {
        CompletableFuture<Choice> future = pending.get(clarificationId);
        if (future == null) {
            logger.warn("收到未注册的任务澄清响应: clarificationId={}", clarificationId);
            return false;
        }
        boolean delegated = selection == null || selection.isBlank() || "delegate".equalsIgnoreCase(selection.trim());
        return future.complete(new Choice(delegated ? null : selection.trim(), note, delegated));
    }

    public Choice await(String clarificationId) {
        CompletableFuture<Choice> future = pending.get(clarificationId);
        if (future == null) {
            return new Choice(null, null, true);
        }
        try {
            return future.get(timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("任务澄清超时，交由 Agent 自行决定: clarificationId={}, timeout={}s",
                    clarificationId, timeoutSeconds);
            return new Choice(null, null, true);
        } catch (Exception e) {
            logger.warn("等待任务澄清失败，交由 Agent 自行决定: clarificationId={}, error={}",
                    clarificationId, e.getMessage());
            return new Choice(null, null, true);
        } finally {
            pending.remove(clarificationId);
        }
    }
}
