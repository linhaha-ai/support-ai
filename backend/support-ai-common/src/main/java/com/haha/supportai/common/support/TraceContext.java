package com.haha.supportai.common.support;

import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class TraceContext {

    private static final String MDC_KEY = "traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private TraceContext() {
    }

    public static String currentOrNull() {
        return normalize(MDC.get(MDC_KEY));
    }

    public static void run(String traceId, Runnable action) {
        Objects.requireNonNull(action, "追踪上下文任务不能为空");
        String previous = MDC.get(MDC_KEY);
        String normalized = normalize(traceId);
        if (normalized == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, normalized);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    private static String normalize(String traceId) {
        if (traceId == null || !SAFE_TRACE_ID.matcher(traceId).matches()) {
            return null;
        }
        return traceId;
    }
}
