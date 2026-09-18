package com.haha.supportai.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CsrfTokenService {

    public static final String HEADER_NAME = "X-CSRF-TOKEN";
    public static final String PARAMETER_NAME = "_csrf";
    private static final String SESSION_ATTRIBUTE = CsrfTokenService.class.getName() + ".TOKEN";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String getOrCreateToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existingToken = session.getAttribute(SESSION_ATTRIBUTE);
        if (existingToken instanceof String token && StringUtils.hasText(token)) {
            return token;
        }
        return rotateToken(request);
    }

    public String rotateToken(HttpServletRequest request) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, token);
        return token;
    }

    public boolean isValid(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof String expectedToken)) {
            return false;
        }
        String submittedToken = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(submittedToken)) {
            submittedToken = request.getParameter(PARAMETER_NAME);
        }
        return StringUtils.hasText(submittedToken)
                && MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                        submittedToken.getBytes(StandardCharsets.UTF_8));
    }

    public void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }
}
