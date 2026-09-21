package com.haha.supportai.config;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.data.knowledge.repository.KbMemberRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.service.AuthenticationService;
import com.haha.supportai.knowledge.document.service.DocumentUploadPersistenceService;
import com.haha.supportai.knowledge.document.service.DocumentQueryService;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import com.haha.supportai.knowledge.service.KnowledgeBaseService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "support.ai.ingestion.worker.enabled=false"
})
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    private static final String DOCUMENTATION_PASSWORD = "documentation-test-password";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AppUserRepository appUserRepository;

    @MockBean
    KnowledgeBaseRepository knowledgeBaseRepository;

    @MockBean
    KnowledgeDocumentRepository knowledgeDocumentRepository;

    @MockBean
    DocumentVersionRepository documentVersionRepository;

    @MockBean
    DocumentChunkRepository documentChunkRepository;

    @MockBean
    IngestionJobRepository ingestionJobRepository;

    @MockBean
    KbMemberRepository kbMemberRepository;

    @MockBean
    AuthenticationService authenticationService;

    @MockBean
    KnowledgeBaseService knowledgeBaseService;

    @MockBean
    DocumentUploadService documentUploadService;

    @MockBean
    DocumentQueryService documentQueryService;

    @MockBean
    DocumentUploadPersistenceService documentUploadPersistenceService;

    @MockBean
    IngestionJobQueryService ingestionJobQueryService;

    @Test
    void authenticatedUserCanReadCompleteCurrentApiContract() throws Exception {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-18T00:00:00Z");
        AppUser appUser = new AppUser(userId, "admin", "unused-hash", "系统管理员", UserRoleEnum.ADMIN, true, now, now);
        AuthenticatedUser authenticatedUser = AuthenticatedUser.from(appUser);
        when(authenticationService.authenticate("admin", DOCUMENTATION_PASSWORD)).thenReturn(authenticatedUser);
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(appUser));

        AuthenticatedRequest authenticatedRequest = login();
        MvcResult apiDocsResult = mvc.perform(get("/v3/api-docs")
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("3.0.1"))
                .andExpect(jsonPath("$.info.title").value("SupportAI API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$['paths']['/api/auth/csrf']['get']['operationId']")
                        .value("getCsrfToken"))
                .andExpect(jsonPath("$['paths']['/api/auth/csrf']['get']['responses']['200']"
                        + "['content']['application/json']['schema']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['operationId']").value("login"))
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['parameters'][0]['name']")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['requestBody']['required']").value(true))
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['requestBody']"
                        + "['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/LoginRequest"))
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['responses']['200']"
                        + "['content']['application/json']['schema']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/logout']['post']['operationId']").value("logout"))
                .andExpect(jsonPath("$['paths']['/api/auth/logout']['post']['responses']['204']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/me']['get']['operationId']").value("getCurrentUser"))
                .andExpect(jsonPath("$['paths']['/api/auth/me']['get']['responses']['200']"
                        + "['content']['application/json']['schema']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/me']['get']['security'][0]['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases']['get']['operationId']")
                        .value("listAccessibleKnowledgeBases"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases']['post']['operationId']")
                        .value("createKnowledgeBase"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases']['post']['parameters'][0]['name']")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases']['post']['requestBody']"
                        + "['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CreateKnowledgeBaseRequest"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases']['post']['responses']['201']").exists())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}']['get']['operationId']")
                        .value("getKnowledgeBase"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}']['patch']['operationId']")
                        .value("updateKnowledgeBase"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}']['patch']['requestBody']"
                        + "['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/UpdateKnowledgeBaseRequest"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']['operationId']")
                        .value("uploadKnowledgeDocument"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']"
                        + "['parameters'][?(@.name == 'X-CSRF-TOKEN')]").exists())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']"
                        + "['requestBody']['required']").value(true))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']"
                        + "['requestBody']['content']['multipart/form-data']['schema']['required']")
                        .value(hasItem("file")))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']"
                        + "['requestBody']['content']['multipart/form-data']['schema']['properties']['file']"
                        + "['format']").value("binary"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['post']"
                        + "['responses']['202']").exists())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['get']['operationId']")
                        .value("listKnowledgeDocuments"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/documents']['get']['security'][0]"
                        + "['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/documents/{documentId}']['get']['operationId']")
                        .value("getKnowledgeDocument"))
                .andExpect(jsonPath("$['paths']['/api/documents/{documentId}/versions']['get']['operationId']")
                        .value("listKnowledgeDocumentVersions"))
                .andExpect(jsonPath("$['paths']['/api/documents/{documentId}/versions/{versionId}/chunks']"
                        + "['get']['operationId']").value("listKnowledgeDocumentChunks"))
                .andExpect(jsonPath("$['paths']['/api/documents/{documentId}/versions/{versionId}/content']"
                        + "['get']['operationId']").value("getKnowledgeDocumentContent"))
                .andExpect(jsonPath("$['paths']['/api/documents/{documentId}/versions/{versionId}/content']"
                        + "['get']['security'][0]['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['operationId']")
                        .value("getIngestionJob"))
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['security'][0]"
                        + "['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['responses']['200']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/admin/knowledge-bases']['get']['operationId']")
                        .value("listManagedKnowledgeBases"))
                .andExpect(jsonPath("$['paths']['/api/admin/users']['get']['operationId']")
                        .value("listUsersForAdministration"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members']['get']['operationId']")
                        .value("listKnowledgeBaseMembers"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members/{userId}']['put']['operationId']")
                        .value("upsertKnowledgeBaseMember"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members/{userId}']['put']"
                        + "['parameters'][?(@.name == 'X-CSRF-TOKEN')]").exists())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members/{userId}']['put']"
                        + "['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/UpdateKnowledgeBaseMemberRequest"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members/{userId}']['delete']"
                        + "['operationId']").value("revokeKnowledgeBaseMember"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/members/{userId}']['delete']"
                        + "['responses']['204']").exists())
                .andExpect(jsonPath("$['components']['securitySchemes']['SaTokenCookie']['type']")
                        .value("apiKey"))
                .andExpect(jsonPath("$['components']['securitySchemes']['SaTokenCookie']['in']").value("cookie"))
                .andExpect(jsonPath("$['components']['securitySchemes']['SaTokenCookie']['name']")
                        .value("support-ai-token"))
                .andExpect(jsonPath("$['components']['schemas']['LoginRequest']['properties']['password']['writeOnly']")
                        .value(true))
                .andReturn();

        JsonNode apiDocs = objectMapper.readTree(apiDocsResult.getResponse().getContentAsString());
        assertResponseDataSchema(apiDocs, "/api/auth/csrf", "get", "CsrfTokenResponse");
        assertResponseDataSchema(apiDocs, "/api/auth/login", "post", "CurrentUserResponse");
        assertResponseDataSchema(apiDocs, "/api/auth/me", "get", "CurrentUserResponse");
        assertResponseDataArrayItemSchema(apiDocs, "/api/knowledge-bases", "get",
                "KnowledgeBaseSummaryResponse");
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases", "post", "201",
                "KnowledgeBaseDetailResponse");
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases/{kbId}", "get",
                "KnowledgeBaseDetailResponse");
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases/{kbId}", "patch",
                "KnowledgeBaseManagementResponse");
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases/{kbId}/documents", "post", "202",
                "DocumentUploadReceiptResponse");
        assertResponseDataArrayItemSchema(apiDocs, "/api/knowledge-bases/{kbId}/documents", "get",
                "DocumentSummaryResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}", "get",
                "DocumentDetailResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions", "get",
                "DocumentVersionPageResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions/{versionId}/chunks", "get",
                "DocumentChunkPageResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions/{versionId}/content", "get",
                "DocumentContentResponse");
        assertResponseDataSchema(apiDocs, "/api/ingestion-jobs/{jobId}", "get",
                "IngestionJobStatusResponse");
        assertResponseDataArrayItemSchema(apiDocs, "/api/admin/knowledge-bases", "get",
                "KnowledgeBaseManagementResponse");
        assertResponseDataArrayItemSchema(apiDocs, "/api/admin/users", "get", "UserSummaryResponse");
        assertResponseDataArrayItemSchema(apiDocs, "/api/knowledge-bases/{kbId}/members", "get",
                "KnowledgeBaseMemberResponse");
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases/{kbId}/members/{userId}", "put",
                "KnowledgeBaseMemberResponse");

        assertThat(apiDocs.path("components").path("schemas").path("UserSummaryResponse")
                .path("properties").has("passwordHash")).isFalse();

        mvc.perform(get("/v3/api-docs/actuator")
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/health']").exists());

        mvc.perform(get("/doc.html")
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    private void assertResponseDataSchema(JsonNode apiDocs, String path, String method, String expectedSchemaName) {
        assertResponseDataSchema(apiDocs, path, method, "200", expectedSchemaName);
    }

    private void assertResponseDataSchema(JsonNode apiDocs, String path, String method, String status,
            String expectedSchemaName) {
        JsonNode responseSchema = apiDocs.path("paths").path(path).path(method).path("responses").path(status)
                .path("content").path(MediaType.APPLICATION_JSON_VALUE).path("schema");
        JsonNode dataSchema = findPropertySchema(apiDocs, responseSchema, "data");
        String expectedReference = "#/components/schemas/" + expectedSchemaName;
        if (!expectedReference.equals(dataSchema.path("$ref").asText())) {
            throw new AssertionError(path + " 的成功响应 data 应引用 " + expectedSchemaName);
        }
    }

    private void assertResponseDataArrayItemSchema(JsonNode apiDocs, String path, String method,
            String expectedSchemaName) {
        JsonNode responseSchema = apiDocs.path("paths").path(path).path(method).path("responses").path("200")
                .path("content").path(MediaType.APPLICATION_JSON_VALUE).path("schema");
        JsonNode dataSchema = findPropertySchema(apiDocs, responseSchema, "data");
        String expectedReference = "#/components/schemas/" + expectedSchemaName;
        if (!"array".equals(dataSchema.path("type").asText())
                || !expectedReference.equals(dataSchema.path("items").path("$ref").asText())) {
            throw new AssertionError(path + " 的成功响应 data 数组元素应引用 " + expectedSchemaName);
        }
    }

    private JsonNode findPropertySchema(JsonNode apiDocs, JsonNode schema, String propertyName) {
        JsonNode resolvedSchema = resolveSchema(apiDocs, schema);
        JsonNode propertySchema = resolvedSchema.path("properties").path(propertyName);
        if (!propertySchema.isMissingNode()) {
            return propertySchema;
        }
        for (JsonNode nestedSchema : resolvedSchema.path("allOf")) {
            JsonNode nestedProperty = findPropertySchema(apiDocs, nestedSchema, propertyName);
            if (!nestedProperty.isMissingNode()) {
                return nestedProperty;
            }
        }
        return propertySchema;
    }

    private JsonNode resolveSchema(JsonNode apiDocs, JsonNode schema) {
        String reference = schema.path("$ref").asText();
        String prefix = "#/components/schemas/";
        if (reference.startsWith(prefix)) {
            return apiDocs.path("components").path("schemas").path(reference.substring(prefix.length()));
        }
        return schema;
    }

    private AuthenticatedRequest login() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .session(session)
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", DOCUMENTATION_PASSWORD)
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();
        return new AuthenticatedRequest((MockHttpSession) loginResult.getRequest().getSession(false),
                loginCookie(loginResult));
    }

    private Cookie loginCookie(MvcResult loginResult) {
        String prefix = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME + "=";
        String setCookie = loginResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(prefix))
                .filter(header -> !header.contains("Max-Age=0"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new AssertionError("登录响应缺少 Sa-Token Cookie"));
        int valueEnd = setCookie.indexOf(';');
        String tokenValue = setCookie.substring(prefix.length(), valueEnd < 0 ? setCookie.length() : valueEnd);
        return new Cookie(IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME, tokenValue);
    }

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie) {
    }
}
