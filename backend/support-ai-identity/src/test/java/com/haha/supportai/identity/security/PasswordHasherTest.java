package com.haha.supportai.identity.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashesAndVerifiesPasswordWithoutStoringPlainText() {
        String password = "local-test-password";

        String passwordHash = passwordHasher.hash(password);

        assertThat(passwordHash).startsWith("$2").doesNotContain(password);
        assertThat(passwordHasher.matches(password, passwordHash)).isTrue();
        assertThat(passwordHasher.matches("wrong-password", passwordHash)).isFalse();
    }

    @Test
    void rejectsPasswordsBeyondBcryptByteLimit() {
        String oversizedPassword = "密".repeat(25);

        assertThatThrownBy(() -> passwordHasher.hash(oversizedPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("密码必须为 1 到 72 个 UTF-8 字节");
        assertThat(passwordHasher.matches(oversizedPassword, passwordHasher.hash("valid-password"))).isFalse();
    }
}
