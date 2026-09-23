package com.haha.supportai.assistant.ticket;

import java.time.OffsetDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
@AutoConfigureMockMvc
class TicketDatabaseIT {

    private static final UUID DEMO_KNOWLEDGE_BASE_ID =
            UUID.fromString("25000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeAll
    static void initializeDemoData(@Autowired DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/identity/init_app_user.sql"),
                new ClassPathResource("db/seed/assistant/init_ticket.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Test
    void seededMemberReadsCurrentStatusAndSeesSubsequentDatabaseChange() throws Exception {
        AuthenticatedRequest support1 = login("support1", "support1");
        String originalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE ticket_no = 'T10086'", String.class);
        OffsetDateTime originalUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM ticket WHERE ticket_no = 'T10086'", OffsetDateTime.class);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-09-23T02:30:00Z");
        try {
            JsonNode initial = queryTicket(support1, "T10086");
            assertThat(initial.path("status").asText()).isEqualTo(originalStatus);
            assertThat(initial.path("knowledgeBaseId").asText()).isEqualTo(DEMO_KNOWLEDGE_BASE_ID.toString());
            assertThat(initial.path("knowledgeBaseName").asText()).isEqualTo("模拟订单系统工单");
            assertThat(initial.path("queriedAt").asText()).isNotBlank();

            jdbcTemplate.update("""
                    -- ticket：模拟外部业务系统更新状态，验证下一次查询不复用旧结果。
                    UPDATE ticket
                    SET status = 'RESOLVED', owner_name = '升级支持工程师', updated_at = ?
                    WHERE ticket_no = 'T10086'
                    """, changedAt);

            JsonNode changed = queryTicket(support1, "t10086");
            assertThat(changed.path("status").asText()).isEqualTo("RESOLVED");
            assertThat(changed.path("ownerName").asText()).isEqualTo("升级支持工程师");
            assertThat(OffsetDateTime.parse(changed.path("updatedAt").asText()).toInstant())
                    .isEqualTo(changedAt.toInstant());
        } finally {
            jdbcTemplate.update("""
                    -- ticket：恢复种子工单原始状态，保持测试之间相互独立。
                    UPDATE ticket
                    SET status = ?, owner_name = '支持工程师2', updated_at = ?
                    WHERE ticket_no = 'T10086'
                    """, originalStatus, originalUpdatedAt);
        }
    }

    @Test
    void missingTicketAndUnauthorizedAdministratorReturnTheSameNotFoundResponse() throws Exception {
        AuthenticatedRequest support1 = login("support1", "support1");
        AuthenticatedRequest administrator = login("admin", "admin");

        assertHidden(mvc.perform(get("/api/tickets/{ticketNo}", "T99999")
                .session(support1.session()).cookie(support1.cookie())).andReturn());
        assertHidden(mvc.perform(get("/api/tickets/{ticketNo}", "T10086")
                .session(administrator.session()).cookie(administrator.cookie())).andReturn());
    }

    @Test
    void disabledKnowledgeBaseIsHiddenAndTicketSeedIsIdempotent() throws Exception {
        AuthenticatedRequest support2 = login("support2", "support2");
        try {
            jdbcTemplate.update("UPDATE knowledge_base SET status = 'DISABLED' WHERE id = ?",
                    DEMO_KNOWLEDGE_BASE_ID);
            assertHidden(mvc.perform(get("/api/tickets/{ticketNo}", "T10087")
                    .session(support2.session()).cookie(support2.cookie())).andReturn());
        } finally {
            jdbcTemplate.update("UPDATE knowledge_base SET status = 'ACTIVE' WHERE id = ?",
                    DEMO_KNOWLEDGE_BASE_ID);
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/assistant/init_ticket.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket WHERE kb_id = ?", Integer.class,
                DEMO_KNOWLEDGE_BASE_ID)).isEqualTo(30);
    }

    private JsonNode queryTicket(AuthenticatedRequest request, String ticketNo) throws Exception {
        MvcResult result = mvc.perform(get("/api/tickets/{ticketNo}", ticketNo)
                        .session(request.session()).cookie(request.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return responseData(result);
    }

    private void assertHidden(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.path("message").asText()).isEqualTo("资源不存在或不可访问");
        assertThat(response.path("traceId").asText()).isNotBlank();
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
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
        return new AuthenticatedRequest(currentCsrf.session(), cookie);
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

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie) {
    }
}
