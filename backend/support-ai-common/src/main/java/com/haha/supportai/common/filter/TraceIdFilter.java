package com.haha.supportai.common.filter;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_NAME = TraceIdFilter.class.getName() + ".TRACE_ID";
    public static final String HEADER_NAME = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        response.setHeader(HEADER_NAME, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    public static String getTraceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(ATTRIBUTE_NAME);
        return traceId == null ? "unknown" : traceId.toString();
    }

    public static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "unknown" : traceId;
    }
}
