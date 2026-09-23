package com.haha.supportai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.ticket.service.TicketQueryService;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import com.haha.supportai.conversation.service.stream.ChatMessageStreamService;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.data.assistant.repository.TicketRepository;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
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
import com.haha.supportai.knowledge.document.service.DocumentUploadPersistenceService;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobControlService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import com.haha.supportai.knowledge.service.KnowledgeBaseService;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
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
        "spring.ai.dashscope.api-key=", "support.ai.ingestion.worker.enabled=false"
})
@AutoConfigureMockMvc
class HealthSecurityTest {

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
    KnowledgeBaseService knowledgeBaseService;

    @MockBean
    DocumentUploadService documentUploadService;

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

    @MockBean
    TicketRepository ticketRepository;

    @MockBean
    ToolCallRepository toolCallRepository;

    @MockBean
    TicketQueryService ticketQueryService;

    @Test
    void publicHealthShowsOnlyStatus() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anonymousBusinessEntriesRequireAuthenticationAndWritesRequireCsrf() throws Exception {
        for (String path : new String[] { "/api/chat", "/api/health/db", "/api/env", "/actuator", "/doc.html",
                "/v3/api-docs", "/api/ingestion-jobs/550e8400-e29b-41d4-a716-446655440000",
                "/api/chat/sessions", "/api/knowledge-bases/550e8400-e29b-41d4-a716-446655440000/retrieval-tests",
                "/api/chunks/550e8400-e29b-41d4-a716-446655440000/source",
                "/api/tickets/T10086",
                "/" }) {
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
        for (String path : new String[] {
                "/api/ingestion-jobs/550e8400-e29b-41d4-a716-446655440000/retry",
                "/api/ingestion-jobs/550e8400-e29b-41d4-a716-446655440000/cancel" }) {
            mvc.perform(post(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        mvc.perform(post("/api/knowledge-bases/550e8400-e29b-41d4-a716-446655440000/retrieval-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"退款状态\",\"topK\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        String messagePath = "/api/chat/sessions/550e8400-e29b-41d4-a716-446655440001/messages";
        String messageBody = "{\"clientRequestId\":\"550e8400-e29b-41d4-a716-446655440002\","
                + "\"content\":\"如何排查？\"}";
        mvc.perform(post(messagePath).contentType(MediaType.APPLICATION_JSON).content(messageBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        var csrfRequest = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfRequest.getResponse().getContentAsString()).path("data");
        mvc.perform(post(messagePath)
                        .session((MockHttpSession) csrfRequest.getRequest().getSession(false))
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
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
