package com.haha.supportai.identity.security;

import java.nio.charset.StandardCharsets;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PasswordHasher {

    private static final int LOG_ROUNDS = 12;
    private static final int MAX_PASSWORD_BYTES = 72;

    public String hash(String password) {
        if (!isSupported(password)) {
            throw new IllegalArgumentException("密码必须为 1 到 72 个 UTF-8 字节");
        }
        return BCrypt.hashpw(password, BCrypt.gensalt(LOG_ROUNDS));
    }

    public boolean matches(String password, String passwordHash) {
        if (!isSupported(password) || !StringUtils.hasText(passwordHash)) {
            return false;
        }
        try {
            return BCrypt.checkpw(password, passwordHash);
        } catch (IllegalArgumentException exception) {
            // 数据库中的摘要格式异常时按认证失败处理，不向外泄露存储细节。
            return false;
        }
    }

    private boolean isSupported(String password) {
        return StringUtils.hasText(password)
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
