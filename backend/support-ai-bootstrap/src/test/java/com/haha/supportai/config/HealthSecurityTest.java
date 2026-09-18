package com.haha.supportai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key="
})
@AutoConfigureMockMvc
class HealthSecurityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AppUserRepository appUserRepository;

    @Test
    void publicHealthShowsOnlyStatus() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anonymousBusinessEntriesRequireAuthenticationAndWritesRequireCsrf() throws Exception {
        for (String path : new String[] { "/api/chat", "/api/health/db", "/api/env", "/actuator", "/doc.html",
                "/v3/api-docs", "/" }) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("请先登录"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        mvc.perform(post("/api/health"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void malformedLoginJsonUsesUniformErrorResponse() throws Exception {
        var csrfRequest = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfRequest.getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/auth/login")
                        .session((MockHttpSession) csrfRequest.getRequest().getSession(false))
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求内容无法解析"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void identityAdviceHandlesInvalidCredentialsBeforeCommonFallback() throws Exception {
        var csrfRequest = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfRequest.getResponse().getContentAsString()).path("data");

        mvc.perform(post("/api/auth/login")
                        .session((MockHttpSession) csrfRequest.getRequest().getSession(false))
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "missing-user")
                                .put("password", "invalid-test-password")
                                .toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
