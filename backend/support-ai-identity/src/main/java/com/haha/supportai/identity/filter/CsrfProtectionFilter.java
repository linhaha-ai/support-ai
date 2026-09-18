package com.haha.supportai.identity.filter;

import java.io.IOException;
import java.util.Set;

import com.haha.supportai.common.support.SecurityResponseWriter;
import com.haha.supportai.identity.service.CsrfTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final CsrfTokenService csrfTokenService;
    private final SecurityResponseWriter responseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && !csrfTokenService.isValid(request)) {
            responseWriter.write(request, response, HttpStatus.FORBIDDEN.value(), "CSRF_INVALID", "CSRF 令牌无效");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
