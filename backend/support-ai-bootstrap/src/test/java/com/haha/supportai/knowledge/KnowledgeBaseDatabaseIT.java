package com.haha.supportai.knowledge;

import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.storage.PostgresFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@AutoConfigureMockMvc
class KnowledgeBaseDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeAll
    static void initializeSupportAccounts(@Autowired DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/identity/init_app_user.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void administratorCreatesKnowledgeBaseAndCreatorReceivesWriteMembership() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        AuthenticatedRequest supportUser = login("support1", "support1");
        UUID adminId = userId("admin");
        UUID knowledgeBaseId = null;
        try {
            JsonNode created = create(admin, "支付故障手册", "支付链路故障处理资料");
            knowledgeBaseId = UUID.fromString(created.path("id").asText());

            assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(created.path("currentPermission").asText()).isEqualTo("WRITE");
            assertThat(created.path("revision").asLong()).isZero();
            assertThat(created.path("documentCount").asLong()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT owner_id FROM knowledge_base WHERE id = ?", UUID.class, knowledgeBaseId))
                    .isEqualTo(adminId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT permission FROM kb_member WHERE kb_id = ? AND user_id = ?",
                    String.class, knowledgeBaseId, adminId)).isEqualTo("WRITE");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT granted_by FROM kb_member WHERE kb_id = ? AND user_id = ?",
                    UUID.class, knowledgeBaseId, adminId)).isEqualTo(adminId);

            JsonNode accessibleList = responseData(mvc.perform(get("/api/knowledge-bases")
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(findById(accessibleList, knowledgeBaseId).path("permission").asText()).isEqualTo("WRITE");

            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(knowledgeBaseId.toString()))
                    .andExpect(jsonPath("$.data.currentPermission").value("WRITE"))
                    .andExpect(jsonPath("$.data.documentCount").value(0));

            mvc.perform(post("/api/knowledge-bases")
                            .session(supportUser.session()).cookie(supportUser.cookie())
                            .header(supportUser.csrfHeader(), supportUser.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.createObjectNode().put("name", "越权创建").toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        } finally {
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void authorizationDisableRestoreAndRevisionConflictFollowF03Boundaries() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        AuthenticatedRequest support1 = login("support1", "support1");
        AuthenticatedRequest support2 = login("support2", "support2");
        UUID adminId = userId("admin");
        UUID support1Id = userId("support1");
        UUID knowledgeBaseId = null;
        try {
            JsonNode created = create(admin, "接口规范", "内部接口说明");
            knowledgeBaseId = UUID.fromString(created.path("id").asText());
            jdbcTemplate.update("""
                    INSERT INTO kb_member (kb_id, user_id, permission, granted_by, created_at)
                    VALUES (?, ?, 'READ', ?, CURRENT_TIMESTAMP)
                    """, knowledgeBaseId, support1Id, adminId);

            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(support1.session()).cookie(support1.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentPermission").value("READ"));
            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(support2.session()).cookie(support2.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));

            // 负责人和管理员身份都不能替代成员关系，但管理员仍可通过管理入口维护元数据。
            jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?", knowledgeBaseId, adminId);
            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(admin.session()).cookie(admin.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
            assertThat(findById(managedList(admin, null), knowledgeBaseId).path("status").asText())
                    .isEqualTo("ACTIVE");

            JsonNode disabled = update(admin, knowledgeBaseId, 0, "DISABLED", null);
            assertThat(disabled.path("revision").asLong()).isEqualTo(1);
            assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");
            assertThat(findById(managedList(admin, "DISABLED"), knowledgeBaseId).isMissingNode()).isFalse();

            JsonNode supportList = responseData(mvc.perform(get("/api/knowledge-bases")
                            .session(support1.session()).cookie(support1.cookie()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(findById(supportList, knowledgeBaseId).isMissingNode()).isTrue();
            mvc.perform(get("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(support1.session()).cookie(support1.cookie()))
                    .andExpect(status().isNotFound());

            mvc.perform(patch("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(admin.session()).cookie(admin.cookie())
                            .header(admin.csrfHeader(), admin.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.createObjectNode()
                                    .put("revision", 0)
                                    .put("name", "过期修改")
                                    .toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_CONFLICT"));

            JsonNode restored = update(admin, knowledgeBaseId, 1, "ACTIVE", "接口规范（恢复）");
            assertThat(restored.path("revision").asLong()).isEqualTo(2);
            JsonNode restoredList = responseData(mvc.perform(get("/api/knowledge-bases")
                            .session(support1.session()).cookie(support1.cookie()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(findById(restoredList, knowledgeBaseId).path("permission").asText()).isEqualTo("READ");

            mvc.perform(patch("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                            .session(support1.session()).cookie(support1.cookie())
                            .header(support1.csrfHeader(), support1.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.createObjectNode()
                                    .put("revision", 2)
                                    .put("status", "DISABLED")
                                    .toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        } finally {
            deleteKnowledgeBase(knowledgeBaseId);
        }
    }

    @Test
    void invalidManagementStatusUsesUniformValidationError() throws Exception {
        AuthenticatedRequest admin = login("admin", "admin");
        mvc.perform(get("/api/admin/knowledge-bases").param("status", "ARCHIVED")
                        .session(admin.session()).cookie(admin.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JsonNode create(AuthenticatedRequest request, String name, String description) throws Exception {
        MvcResult result = mvc.perform(post("/api/knowledge-bases")
                        .session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("name", name)
                                .put("description", description)
                                .toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return responseData(result);
    }

    private JsonNode update(AuthenticatedRequest request, UUID knowledgeBaseId, long revision,
            String statusValue, String name) throws Exception {
        var body = objectMapper.createObjectNode().put("revision", revision).put("status", statusValue);
        if (name != null) {
            body.put("name", name);
        }
        MvcResult result = mvc.perform(patch("/api/knowledge-bases/{kbId}", knowledgeBaseId)
                        .session(request.session()).cookie(request.cookie())
                        .header(request.csrfHeader(), request.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return responseData(result);
    }

    private JsonNode managedList(AuthenticatedRequest request, String statusValue) throws Exception {
        var builder = get("/api/admin/knowledge-bases").session(request.session()).cookie(request.cookie());
        if (statusValue != null) {
            builder.param("status", statusValue);
        }
        return responseData(mvc.perform(builder).andExpect(status().isOk()).andReturn());
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode findById(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("id").asText())) {
                return value;
            }
        }
        return objectMapper.missingNode();
    }

    private UUID userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
    }

    private void deleteKnowledgeBase(UUID knowledgeBaseId) {
        if (knowledgeBaseId != null) {
            jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", knowledgeBaseId);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
        }
    }

    private AuthenticatedRequest login(String username, String password) throws Exception {
        SessionCsrf initialCsrf = csrf(null, null);
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .session(initialCsrf.session())
                        .header(initialCsrf.headerName(), initialCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", username)
                                .put("password", password)
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = loginCookie(loginResult);
        SessionCsrf currentCsrf = csrf((MockHttpSession) loginResult.getRequest().getSession(false), cookie);
        return new AuthenticatedRequest(currentCsrf.session(), cookie, currentCsrf.headerName(), currentCsrf.token());
    }

    private SessionCsrf csrf(MockHttpSession session, Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        if (cookie != null) {
            request.cookie(cookie);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode data = responseData(result);
        return new SessionCsrf(data.path("token").asText(), data.path("headerName").asText(),
                (MockHttpSession) result.getRequest().getSession(false));
    }

    private Cookie loginCookie(MvcResult loginResult) {
        String prefix = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME + "=";
        String setCookie = loginResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(prefix))
                .filter(header -> !header.contains("Max-Age=0"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new AssertionError("登录响应缺少 Sa-Token Cookie"));
        int valueEnd = setCookie.indexOf(';');
        return new Cookie(IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME,
                setCookie.substring(prefix.length(), valueEnd < 0 ? setCookie.length() : valueEnd));
    }

    private record SessionCsrf(String token, String headerName, MockHttpSession session) {
    }

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie, String csrfHeader, String csrfToken) {
    }
}
