package com.haha.supportai.identity;

import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.identity.security.PasswordHasher;
import com.haha.supportai.storage.PostgresFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
@SpringBootTest
@AutoConfigureMockMvc
class AuthDatabaseIT {

    private static final String TOKEN_COOKIE_NAME = "support-ai-token";
    private static final String ADMIN_PASSWORD = "admin";
    private static final String USER1_PASSWORD = "support1";
    private static final String USER2_PASSWORD = "support2";

    @Container
    static final PostgreSQLContainer<?> DATABASE = PostgresFixture.container();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeAll
    static void explicitlyInitializeAccountsAfterApplicationStartup(@Autowired DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // 普通启动仅迁移表结构，演示账号由测试显式执行独立 SQL 初始化。
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
        executeAccountSeed(dataSource);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordHasher passwordHasher;

    @Test
    void initializesThreeHashedUsersAndRejectsInvalidCredentials() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(3);
        var passwordHashes = jdbcTemplate.queryForList("SELECT password_hash FROM app_user ORDER BY username", String.class);
        assertThat(passwordHashes)
                .allMatch(hash -> hash.startsWith("$2"))
                .doesNotContain(ADMIN_PASSWORD, USER1_PASSWORD, USER2_PASSWORD);
        for (String username : List.of("admin", "support1", "support2")) {
            String passwordHash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM app_user WHERE username = ?", String.class, username);
            assertThat(passwordHasher.matches(username, passwordHash)).isTrue();
        }
        executeAccountSeed(dataSource);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("SELECT password_hash FROM app_user ORDER BY username", String.class))
                .containsExactlyElementsOf(passwordHashes);

        SessionCsrf csrf = csrf();
        mvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("wrong-password", "password_hash"));
    }

    @Test
    void rerunningAccountSeedPreservesExistingAccountChanges() {
        var originalAccount = jdbcTemplate.queryForMap("SELECT * FROM app_user WHERE username = ?", "admin");
        try {
            // 先模拟已使用账号的修改，确认重复执行脚本不会把它恢复为默认值。
            jdbcTemplate.update("""
                    UPDATE app_user
                    SET password_hash = ?, display_name = ?, role = ?, enabled = FALSE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE username = ?
                    """, passwordHasher.hash("changed-account-test-password"), "已修改的展示名称", "USER", "admin");
            var existingAccounts = jdbcTemplate.queryForList("SELECT * FROM app_user ORDER BY username");

            executeAccountSeed(dataSource);

            assertThat(jdbcTemplate.queryForList("SELECT * FROM app_user ORDER BY username"))
                    .containsExactlyElementsOf(existingAccounts);
        } finally {
            jdbcTemplate.update("""
                    UPDATE app_user
                    SET password_hash = ?, display_name = ?, role = ?, enabled = ?, updated_at = ?
                    WHERE id = ?
                    """, originalAccount.get("password_hash"), originalAccount.get("display_name"),
                    originalAccount.get("role"), originalAccount.get("enabled"), originalAccount.get("updated_at"),
                    originalAccount.get("id"));
        }
    }

    @Test
    void logsInRotatesSessionRefreshesCsrfAndLogsOut() throws Exception {
        SessionCsrf initialCsrf = csrf();
        String anonymousSessionId = initialCsrf.session().getId();

        MvcResult login = login("admin", ADMIN_PASSWORD, initialCsrf);
        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        Cookie loginCookie = loginCookie(login);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(anonymousSessionId);
        // 默认密码与用户名相同，按字段检查凭据泄露，允许响应包含合法用户名。
        assertThat(login.getResponse().getContentAsString())
                .doesNotContain("\"password\"", "\"passwordHash\"", "\"password_hash\"");

        SessionCsrf refreshedCsrf = csrf(authenticatedSession);
        assertThat(refreshedCsrf.token()).isNotEqualTo(initialCsrf.token());
        mvc.perform(get("/api/auth/me").session(authenticatedSession).cookie(loginCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mvc.perform(post("/api/auth/logout")
                        .session(authenticatedSession)
                        .cookie(loginCookie)
                        .header(refreshedCsrf.headerName(), refreshedCsrf.token()))
                .andExpect(status().isNoContent());
        assertThat(authenticatedSession.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(loginCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsInvalidCsrfWithoutCreatingOrEndingTheLoginSession() throws Exception {
        SessionCsrf initialCsrf = csrf();
        mvc.perform(post("/api/auth/login")
                        .session(initialCsrf.session())
                        .header(initialCsrf.headerName(), "invalid-csrf-token")
                        .contentType("application/json")
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", ADMIN_PASSWORD)
                                .toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(get("/api/auth/me").session(initialCsrf.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        MvcResult login = login("admin", ADMIN_PASSWORD, initialCsrf);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Cookie loginCookie = loginCookie(login);
        SessionCsrf refreshedCsrf = csrf(session);
        assertThat(refreshedCsrf.token()).isNotEqualTo(initialCsrf.token());

        try {
            // 登录前的合法令牌已轮换，须与缺失或伪造令牌一样拒绝，且不能注销有效登录态。
            for (String rejectedToken : new String[] { null, "invalid-csrf-token", initialCsrf.token() }) {
                var request = post("/api/auth/logout").session(session).cookie(loginCookie);
                if (rejectedToken != null) {
                    request.header(refreshedCsrf.headerName(), rejectedToken);
                }
                mvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                        .andExpect(jsonPath("$.traceId").isNotEmpty());
                mvc.perform(get("/api/auth/me").session(session).cookie(loginCookie))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.username").value("admin"));
            }
        } finally {
            mvc.perform(post("/api/auth/logout")
                            .session(session)
                            .cookie(loginCookie)
                            .header(refreshedCsrf.headerName(), refreshedCsrf.token()))
                    .andExpect(status().isNoContent());
        }
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(loginCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void repeatedLoginReplacesTheCurrentBrowserToken() throws Exception {
        MvcResult firstLogin = login("admin", ADMIN_PASSWORD, csrf());
        MockHttpSession session = (MockHttpSession) firstLogin.getRequest().getSession(false);
        Cookie firstCookie = loginCookie(firstLogin);
        SessionCsrf refreshedCsrf = csrf(session);

        MvcResult secondLogin = login("admin", ADMIN_PASSWORD, refreshedCsrf, firstCookie);
        Cookie secondCookie = loginCookie(secondLogin);
        assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

        mvc.perform(get("/api/auth/me").session(session).cookie(firstCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").session(session).cookie(secondCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void enforcesAuthenticationCsrfAndImmediateAccountDisablement() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"support1\",\"password\":\"ignored\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        MvcResult login = login("support1", USER1_PASSWORD, csrf());
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Cookie loginCookie = loginCookie(login);
        try {
            jdbcTemplate.update("UPDATE app_user SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP WHERE username = ?",
                    "support1");

            SessionCsrf reusableCsrf = csrf(session);
            mvc.perform(get("/api/auth/me").session(session).cookie(loginCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"));
            assertThat(session.isInvalid()).isFalse();
            mvc.perform(get("/api/auth/me").session(session).cookie(loginCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            login("support2", USER2_PASSWORD, reusableCsrf);
        } finally {
            jdbcTemplate.update("UPDATE app_user SET enabled = TRUE, updated_at = CURRENT_TIMESTAMP WHERE username = ?",
                    "support1");
        }
    }

    private static void executeAccountSeed(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/identity/init_app_user.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    private MvcResult login(String username, String password, SessionCsrf csrf) throws Exception {
        return login(username, password, csrf, null);
    }

    private MvcResult login(String username, String password, SessionCsrf csrf, Cookie currentCookie) throws Exception {
        var request = post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType("application/json")
                        .content(objectMapper.createObjectNode()
                                .put("username", username)
                                .put("password", password)
                                .toString());
        if (currentCookie != null) {
            request.cookie(currentCookie);
        }
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
    }

    private Cookie loginCookie(MvcResult login) {
        String prefix = TOKEN_COOKIE_NAME + "=";
        String setCookie = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(prefix))
                .filter(header -> !header.contains("Max-Age=0"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new AssertionError("登录响应缺少 Sa-Token Cookie"));
        assertThat(setCookie).containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .contains("Path=/")
                .doesNotContain("Max-Age", "Expires=");
        int valueEnd = setCookie.indexOf(';');
        String tokenValue = setCookie.substring(prefix.length(), valueEnd < 0 ? setCookie.length() : valueEnd);
        return new Cookie(TOKEN_COOKIE_NAME, tokenValue);
    }

    private SessionCsrf csrf() throws Exception {
        return csrf(null);
    }

    private SessionCsrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new SessionCsrf(data.path("token").asText(), data.path("headerName").asText(),
                (MockHttpSession) result.getRequest().getSession(false));
    }

    private record SessionCsrf(String token, String headerName, MockHttpSession session) {
    }
}
