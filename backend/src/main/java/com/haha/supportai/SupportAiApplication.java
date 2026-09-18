package com.haha.supportai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SupportAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportAiApplication.class, args);
    }
}
