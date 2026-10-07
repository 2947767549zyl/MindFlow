package com.mindflow.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 tokenKey(generationId) 键控的取消令牌注册表。
 * Web 多用户场景下不能使用全局/ThreadLocal 单 token，否则并发用户的取消会互相踩踏。
 */
public final class CancellationContext {
    private static final Map<String, CancellationToken> TOKENS = new ConcurrentHashMap<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun(String tokenKey) {
        CancellationToken token = new CancellationToken();
        TOKENS.put(tokenKey, token);
        return token;
    }

    public static boolean isCancelled(String tokenKey) {
        CancellationToken token = TOKENS.get(tokenKey);
        return token != null && token.isCancelled();
    }

    public static void cancel(String tokenKey) {
        CancellationToken token = TOKENS.get(tokenKey);
        if (token != null) {
            token.cancel();
        }
    }

    public static void clear(String tokenKey) {
        TOKENS.remove(tokenKey);
    }
}
