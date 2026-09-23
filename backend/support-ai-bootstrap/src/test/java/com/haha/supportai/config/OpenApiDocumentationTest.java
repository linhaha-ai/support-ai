package com.haha.supportai.config;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import com.haha.supportai.conversation.service.stream.ChatMessageStreamService;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.data.knowledge.repository.AuthorizedDocumentSourceRepository;
import com.haha.supportai.data.knowledge.repository.AuthorizedRetrievalRepository;
import com.haha.supportai.data.knowledge.repository.KbMemberRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVersionRepository;
import com.haha.supportai.data.knowledge.repository.DocumentChunkRepository;
import com.haha.supportai.data.knowledge.repository.DocumentVectorRepository;
import com.haha.supportai.data.knowledge.repository.IngestionJobRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeBaseRepository;
import com.haha.supportai.data.knowledge.repository.KnowledgeDocumentRepository;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.service.AuthenticationService;
import com.haha.supportai.knowledge.document.service.DocumentUploadPersistenceService;
import com.haha.supportai.knowledge.document.service.DocumentQueryService;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalTestResponse;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeRetrievalUnavailableException;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobControlService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import com.haha.supportai.knowledge.service.KnowledgeBaseService;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
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
    DocumentVectorRepository documentVectorRepository;

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

    @MockBean
    IngestionJobControlService ingestionJobControlService;

    @MockBean
    KnowledgeRetrievalService knowledgeRetrievalService;

    @MockBean
    DocumentSourceQueryService documentSourceQueryService;

    @MockBean
    ChatSessionRepository chatSessionRepository;

    @MockBean
    ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;

    @MockBean
    ChatRunRepository chatRunRepository;

    @MockBean
    ChatMessageRepository chatMessageRepository;

    @MockBean
    MessageSourceRepository messageSourceRepository;

    @MockBean
    AuthorizedDocumentSourceRepository authorizedDocumentSourceRepository;

    @MockBean
    AuthorizedRetrievalRepository authorizedRetrievalRepository;

    @MockBean
    ChatSessionService chatSessionService;

    @MockBean
    ChatRunService chatRunService;

    @MockBean
    ChatMessageStreamService chatMessageStreamService;

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
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/retrieval-tests']['post']['operationId']")
                        .value("testKnowledgeBaseRetrieval"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/retrieval-tests']['post']"
                        + "['parameters'][?(@.name == 'X-CSRF-TOKEN')]").exists())
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/retrieval-tests']['post']"
                        + "['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/KnowledgeRetrievalTestRequest"))
                .andExpect(jsonPath("$['paths']['/api/knowledge-bases/{kbId}/retrieval-tests']['post']['security'][0]"
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
                .andExpect(jsonPath("$['paths']['/api/chunks/{chunkId}/source']['get']['operationId']")
                        .value("getDocumentChunkSource"))
                .andExpect(jsonPath("$['paths']['/api/chunks/{chunkId}/source']['get']['security'][0]"
                        + "['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['operationId']")
                        .value("getIngestionJob"))
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['security'][0]"
                        + "['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}']['get']['responses']['200']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}/retry']['post']['operationId']")
                        .value("retryIngestionJob"))
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}/retry']['post']"
                        + "['parameters'][?(@.name == 'X-CSRF-TOKEN')]").exists())
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}/cancel']['post']['operationId']")
                        .value("cancelIngestionJob"))
                .andExpect(jsonPath("$['paths']['/api/ingestion-jobs/{jobId}/cancel']['post']"
                        + "['parameters'][?(@.name == 'X-CSRF-TOKEN')]").exists())
                .andExpect(jsonPath("$['paths']['/api/chat/sessions']['post']['operationId']")
                        .value("createChatSession"))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions']['post']['parameters'][0]['name']")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions']['post']['requestBody']"
                        + "['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/CreateChatSessionRequest"))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions']['post']['responses']['201']").exists())
                .andExpect(jsonPath("$['paths']['/api/chat/sessions']['post']['security'][0]"
                        + "['SaTokenCookie']").isArray())
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']['operationId']")
                        .value("submitChatMessage"))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']"
                        + "['parameters'][*]['name']").value(hasItem("sessionId")))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']"
                        + "['parameters'][*]['name']").value(hasItem("X-CSRF-TOKEN")))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']['requestBody']"
                        + "['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/SubmitMessageCommand"))
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']['responses']['200']"
                        + "['content']['text/event-stream']").exists())
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']['responses']['200']"
                        + "['content']['application/json']").exists())
                .andExpect(jsonPath("$['paths']['/api/chat/sessions/{sessionId}/messages']['post']['security'][0]"
                        + "['SaTokenCookie']").isArray())
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
                .andExpect(jsonPath("$['components']['schemas']['CreateChatSessionRequest']['properties']['userId']")
                        .doesNotExist())
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
        assertResponseDataSchema(apiDocs, "/api/knowledge-bases/{kbId}/retrieval-tests", "post",
                "KnowledgeRetrievalTestResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}", "get",
                "DocumentDetailResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions", "get",
                "DocumentVersionPageResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions/{versionId}/chunks", "get",
                "DocumentChunkPageResponse");
        assertResponseDataSchema(apiDocs, "/api/documents/{documentId}/versions/{versionId}/content", "get",
                "DocumentContentResponse");
        assertResponseDataSchema(apiDocs, "/api/chunks/{chunkId}/source", "get",
                "DocumentSourceResponse");
        assertResponseDataSchema(apiDocs, "/api/ingestion-jobs/{jobId}", "get",
                "IngestionJobStatusResponse");
        assertResponseDataSchema(apiDocs, "/api/ingestion-jobs/{jobId}/retry", "post",
                "IngestionJobStatusResponse");
        assertResponseDataSchema(apiDocs, "/api/ingestion-jobs/{jobId}/cancel", "post",
                "IngestionJobStatusResponse");
        assertResponseDataSchema(apiDocs, "/api/chat/sessions", "post", "201",
                "ChatSessionResponse");
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

    @Test
    void authenticatedUserCanCreateFixedRagSession() throws Exception {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID knowledgeBaseId = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        AppUser appUser = new AppUser(userId, "admin", "unused-hash", "系统管理员", UserRoleEnum.ADMIN, true,
                now, now);
        when(authenticationService.authenticate("admin", DOCUMENTATION_PASSWORD))
                .thenReturn(AuthenticatedUser.from(appUser));
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(appUser));
        when(chatSessionService.create(any()))
                .thenReturn(new ChatSessionResponse(sessionId, "新会话", ChatSessionModeEnum.RAG,
                        List.of(knowledgeBaseId), now, now));

        AuthenticatedRequest authenticatedRequest = login();
        var body = objectMapper.createObjectNode();
        body.putArray("kbIds").add(knowledgeBaseId.toString());
        body.put("mode", "RAG");
        mvc.perform(post("/api/chat/sessions")
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.mode").value("RAG"))
                .andExpect(jsonPath("$.data.knowledgeBaseIds[0]").value(knowledgeBaseId.toString()));
    }

    @Test
    void repeatedMessageSubmissionReturnsJsonWithoutOpeningAnotherStream() throws Exception {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440041");
        UUID runId = UUID.fromString("550e8400-e29b-41d4-a716-446655440042");
        UUID userMessageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440043");
        UUID assistantMessageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440044");
        UUID clientRequestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440045");
        configureAuthenticatedUser();
        when(chatRunService.prepare(eq(sessionId), any())).thenReturn(new PreparedChatRunResponse(
                runId, sessionId, userMessageId, assistantMessageId, "如何排查退款状态？",
                ChatRunStatusEnum.RUNNING, true));

        AuthenticatedRequest authenticatedRequest = login();
        mvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("clientRequestId", clientRequestId.toString())
                                .put("content", "如何排查退款状态？")
                                .toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.reused").value(true));
        verifyNoInteractions(chatMessageStreamService);
    }

    @Test
    void authenticatedWriterWithCsrfCanRunKnowledgeRetrievalTest() throws Exception {
        UUID knowledgeBaseId = UUID.fromString("550e8400-e29b-41d4-a716-446655440021");
        configureAuthenticatedUser();
        when(knowledgeRetrievalService.testWritableKnowledgeBase(eq(knowledgeBaseId), any()))
                .thenReturn(new KnowledgeRetrievalTestResponse(knowledgeBaseId, "退款状态", 3, 12L, List.of()));

        AuthenticatedRequest authenticatedRequest = login();
        mvc.perform(post("/api/knowledge-bases/{kbId}/retrieval-tests", knowledgeBaseId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"退款状态\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBaseId").value(knowledgeBaseId.toString()))
                .andExpect(jsonPath("$.data.matches").isArray());
    }

    @Test
    void authenticatedMemberCanReadDocumentSourceWithoutCsrf() throws Exception {
        UUID knowledgeBaseId = UUID.fromString("550e8400-e29b-41d4-a716-446655440031");
        UUID documentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440032");
        UUID versionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440033");
        UUID chunkId = UUID.fromString("550e8400-e29b-41d4-a716-446655440034");
        configureAuthenticatedUser();
        when(documentSourceQueryService.getReadableSource(chunkId)).thenReturn(new DocumentSourceResponse(
                knowledgeBaseId, documentId, "退款排查手册", versionId, 2, chunkId, 0, "退款/状态", null,
                0, 10, false, "退款状态仍在处理中"));

        AuthenticatedRequest authenticatedRequest = login();
        mvc.perform(get("/api/chunks/{chunkId}/source", chunkId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentName").value("退款排查手册"))
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.isCurrent").value(false))
                .andExpect(jsonPath("$.data.content").value("退款状态仍在处理中"));
    }

    @Test
    void retrievalTestMapsAccessDeniedAndUnknownFieldsToClientErrors() throws Exception {
        UUID knowledgeBaseId = UUID.fromString("550e8400-e29b-41d4-a716-446655440022");
        configureAuthenticatedUser();
        when(knowledgeRetrievalService.testWritableKnowledgeBase(eq(knowledgeBaseId), any()))
                .thenThrow(new KnowledgeBaseAccessDeniedException());

        AuthenticatedRequest authenticatedRequest = login();
        mvc.perform(post("/api/knowledge-bases/{kbId}/retrieval-tests", knowledgeBaseId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"退款状态\",\"topK\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(post("/api/knowledge-bases/{kbId}/retrieval-tests", knowledgeBaseId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"退款状态\",\"topK\":3,\"kbIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void retrievalTestUnavailableErrorDoesNotExposeModelFailureCause() throws Exception {
        UUID knowledgeBaseId = UUID.fromString("550e8400-e29b-41d4-a716-446655440023");
        configureAuthenticatedUser();
        when(knowledgeRetrievalService.testWritableKnowledgeBase(eq(knowledgeBaseId), any()))
                .thenThrow(new KnowledgeRetrievalUnavailableException(
                        new IllegalStateException("embedding-provider-secret")));

        AuthenticatedRequest authenticatedRequest = login();
        MvcResult result = mvc.perform(post("/api/knowledge-bases/{kbId}/retrieval-tests", knowledgeBaseId)
                        .session(authenticatedRequest.session())
                        .cookie(authenticatedRequest.cookie())
                        .header(authenticatedRequest.csrfHeader(), authenticatedRequest.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"退款状态\",\"topK\":3}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_RETRIEVAL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("知识库检索暂时不可用"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("embedding-provider-secret");
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
        MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        Cookie authenticatedCookie = loginCookie(loginResult);
        MvcResult authenticatedCsrfResult = mvc.perform(get("/api/auth/csrf")
                        .session(authenticatedSession)
                        .cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode authenticatedCsrf = objectMapper.readTree(authenticatedCsrfResult.getResponse().getContentAsString())
                .path("data");
        return new AuthenticatedRequest(authenticatedSession, authenticatedCookie,
                authenticatedCsrf.path("headerName").asText(), authenticatedCsrf.path("token").asText());
    }

    private void configureAuthenticatedUser() {
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T08:00:00Z");
        AppUser appUser = new AppUser(userId, "admin", "unused-hash", "系统管理员", UserRoleEnum.ADMIN, true,
                now, now);
        when(authenticationService.authenticate("admin", DOCUMENTATION_PASSWORD))
                .thenReturn(AuthenticatedUser.from(appUser));
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(appUser));
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

    private record AuthenticatedRequest(MockHttpSession session, Cookie cookie, String csrfHeader, String csrfToken) {
    }
}
