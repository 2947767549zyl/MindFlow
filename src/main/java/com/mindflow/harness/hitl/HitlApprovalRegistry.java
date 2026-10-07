package com.mindflow.harness.hitl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 审批状态机注册表：挂起 Future 等待 WS approval_response 恢复；
 * approve_all 记录在会话维度（普通工具按 tool 名，MCP 可按 server 名），/clear 语义由 clearConversation 承载。
 */
@Service
public class HitlApprovalRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HitlApprovalRegistry.class);

    @Value("${mindflow.hitl.approval-timeout-seconds:120}")
    private long approvalTimeoutSeconds;

    private final Map<String, CompletableFuture<HitlMessages.ApprovalResponse>> pending = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> approvedAllByConversation = new ConcurrentHashMap<>();

    public long approvalTimeoutSeconds() {
        return Math.max(1, approvalTimeoutSeconds);
    }

    public String create(HitlMessages.ApprovalRequest request) {
        CompletableFuture<HitlMessages.ApprovalResponse> future = new CompletableFuture<>();
        pending.put(request.approvalId(), future);
        return request.approvalId();
    }

    public boolean complete(String approvalId, HitlMessages.ApprovalResponse response) {
        CompletableFuture<HitlMessages.ApprovalResponse> future = pending.get(approvalId);
        if (future == null) {
            logger.warn("收到未注册的审批响应: approvalId={}", approvalId);
            return false;
        }
        return future.complete(response);
    }

    public HitlMessages.ApprovalResponse await(String approvalId) {
        CompletableFuture<HitlMessages.ApprovalResponse> future = pending.get(approvalId);
        if (future == null) {
            return new HitlMessages.ApprovalResponse(approvalId, HitlMessages.Decision.REJECT,
                    null, "审批请求不存在或已失效");
        }
        try {
            return future.get(approvalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("审批超时，按拒绝处理: approvalId={}", approvalId);
            return new HitlMessages.ApprovalResponse(approvalId, HitlMessages.Decision.REJECT,
                    null, "审批超时（" + approvalTimeoutSeconds() + "秒），已按拒绝处理");
        } catch (Exception e) {
            logger.warn("等待审批失败: approvalId={}, error={}", approvalId, e.getMessage());
            return new HitlMessages.ApprovalResponse(approvalId, HitlMessages.Decision.REJECT,
                    null, "审批等待异常，已按拒绝处理");
        } finally {
            pending.remove(approvalId);
        }
    }

    public boolean isApprovedAll(String conversationId, String toolName, String serverName) {
        Set<String> approvals = approvedAllByConversation.get(conversationId);
        if (approvals == null) {
            return false;
        }
        if (approvals.contains("tool:" + toolName)) {
            return true;
        }
        return serverName != null && approvals.contains("server:" + serverName);
    }

    public void recordApprovedAll(String conversationId, String toolName, String serverName) {
        Set<String> approvals = approvedAllByConversation.computeIfAbsent(
                conversationId, k -> ConcurrentHashMap.newKeySet());
        approvals.add("tool:" + toolName);
        if (serverName != null) {
            approvals.add("server:" + serverName);
        }
    }

    public void clearConversation(String conversationId) {
        approvedAllByConversation.remove(conversationId);
    }
}
