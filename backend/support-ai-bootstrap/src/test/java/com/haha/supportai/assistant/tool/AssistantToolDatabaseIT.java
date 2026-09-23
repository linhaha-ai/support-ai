package com.haha.supportai.assistant.tool;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolNameConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.entity.dto.GetTicketStatusToolRequest;
import com.haha.supportai.assistant.agent.tool.entity.dto.TicketStatusToolResult;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.agent.tool.service.TicketStatusToolAdapter;
import com.haha.supportai.assistant.agent.tool.service.ToolCallPersistenceService;
import com.haha.supportai.assistant.agent.tool.support.ToolSourceFactory;
import com.haha.supportai.assistant.ticket.entity.dto.TicketToolQueryResult;
import com.haha.supportai.assistant.ticket.service.TicketQueryService;
import com.haha.supportai.data.assistant.entity.po.ToolCall;
import com.haha.supportai.data.assistant.enums.ToolCallStatusEnum;
import com.haha.supportai.data.assistant.repository.ToolCallRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.storage.PostgresFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama", "spring.ai.model.embedding=ollama", "spring.ai.dashscope.enabled=false",
        "spring.ai.dashscope.api-key=", "spring.ai.ollama.base-url=http://127.0.0.1:1",
        "spring.ai.ollama.init.pull-model-strategy=never", "support.ai.ingestion.worker.enabled=false"
})
class AssistantToolDatabaseIT {

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
    TicketStatusToolAdapter ticketStatusToolAdapter;

    @Autowired
    ToolCallPersistenceService persistenceService;

    @Autowired
    ToolCallRepository toolCallRepository;

    @Autowired
    TicketQueryService ticketQueryService;

    @Autowired
    ToolSourceFactory sourceFactory;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void persistsFreshTicketResultsWithMatchingToolSourcesAndSequentialCalls() throws Exception {
        AssistantFixture fixture = createFixture(true);
        String originalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE ticket_no = 'T10086'", String.class);
        String originalOwner = jdbcTemplate.queryForObject(
                "SELECT owner_name FROM ticket WHERE ticket_no = 'T10086'", String.class);
        OffsetDateTime originalUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM ticket WHERE ticket_no = 'T10086'", OffsetDateTime.class);
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-09-23T03:30:00Z");
        try {
            TicketStatusToolResult initial = ticketStatusToolAdapter.execute(fixture.context(),
                    new GetTicketStatusToolRequest("T10086"));
            UUID initialCallId = sourceToolCallId(initial.getSourceHandle());
            ToolCall initialCall = toolCallRepository.findById(initialCallId).orElseThrow();

            assertThat(initialCall.getRunId()).isEqualTo(fixture.runId());
            assertThat(initialCall.getCallNo()).isEqualTo(1);
            assertThat(initialCall.getToolName()).isEqualTo(AssistantToolNameConstant.GET_TICKET_STATUS);
            assertThat(initialCall.getStatus()).isEqualTo(ToolCallStatusEnum.SUCCEEDED);
            assertThat(objectMapper.readTree(initialCall.getArguments()))
                    .isEqualTo(objectMapper.readTree("{\"ticketNo\":\"T10086\"}"));
            assertThat(initialCall.getArguments()).doesNotContain("userId", "sessionId", "sql", "path", "endpoint");
            assertTicketSource(initial.getSourceHandle(), initialCallId, fixture, 1, originalStatus);

            jdbcTemplate.update("""
                    -- ticket：模拟外部业务状态变化，验证每次工具调用均实时查询业务表。
                    UPDATE ticket
                    SET status = 'RESOLVED', owner_name = 'F26 升级工程师', updated_at = ?
                    WHERE ticket_no = 'T10086'
                    """, changedAt);

            TicketStatusToolResult changed = ticketStatusToolAdapter.execute(fixture.context(),
                    new GetTicketStatusToolRequest("t10086"));
            UUID changedCallId = sourceToolCallId(changed.getSourceHandle());
            ToolCall changedCall = toolCallRepository.findById(changedCallId).orElseThrow();

            assertThat(changed.getStatus().name()).isEqualTo("RESOLVED");
            assertThat(changed.getOwnerName()).isEqualTo("F26 升级工程师");
            assertThat(changed.getUpdatedAt().toInstant()).isEqualTo(changedAt.toInstant());
            assertThat(changedCall.getCallNo()).isEqualTo(2);
            assertThat(changedCall.getStatus()).isEqualTo(ToolCallStatusEnum.SUCCEEDED);
            assertTicketSource(changed.getSourceHandle(), changedCallId, fixture, 2, "RESOLVED");
            assertThat(jdbcTemplate.queryForList("""
                    SELECT call_no
                    FROM tool_call
                    WHERE run_id = ?
                    ORDER BY call_no
                    """, Integer.class, fixture.runId())).containsExactly(1, 2);
        } finally {
            jdbcTemplate.update("""
                    -- ticket：恢复演示工单，保持数据库集成测试相互独立。
                    UPDATE ticket
                    SET status = ?, owner_name = ?, updated_at = ?
                    WHERE ticket_no = 'T10086'
                    """, originalStatus, originalOwner, originalUpdatedAt);
            deleteFixture(fixture);
        }
    }

    @Test
    void recordsInvalidMissingAndOutOfSessionCallsWithoutLeakingTicketData() {
        AssistantFixture fixture = createFixture(false);
        try {
            assertToolError(() -> ticketStatusToolAdapter.execute(fixture.context(),
                    new GetTicketStatusToolRequest("T10086")), AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND);
            assertToolError(() -> ticketStatusToolAdapter.execute(fixture.context(),
                    new GetTicketStatusToolRequest("T99999")), AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND);
            assertToolError(() -> ticketStatusToolAdapter.execute(fixture.context(),
                    new GetTicketStatusToolRequest("T/10086")), AssistantToolErrorCodeConstant.INVALID_REQUEST);

            assertThat(jdbcTemplate.queryForList("""
                    SELECT call_no
                    FROM tool_call
                    WHERE run_id = ?
                    ORDER BY call_no
                    """, Integer.class, fixture.runId())).containsExactly(1, 2, 3);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT error_code
                    FROM tool_call
                    WHERE run_id = ?
                    ORDER BY call_no
                    """, String.class, fixture.runId())).containsExactly(
                            AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND,
                            AssistantToolErrorCodeConstant.RESOURCE_NOT_FOUND,
                            AssistantToolErrorCodeConstant.INVALID_REQUEST);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM message_source source
                    INNER JOIN tool_call call ON call.id = source.tool_call_id
                    WHERE call.run_id = ?
                    """, Integer.class, fixture.runId())).isZero();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void rejectsForgedUserToolAndSourceRelationshipsAtThePersistenceBoundary() {
        AssistantFixture fixture = createFixture(true);
        try {
            assertToolError(() -> persistenceService.start(
                    new AssistantToolContext(fixture.runId(), fixture.sessionId(), UUID.randomUUID(),
                            fixture.assistantMessageId()),
                    AssistantToolNameConstant.GET_TICKET_STATUS, Map.of("ticketNo", "T10086")),
                    AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID);
            assertToolError(() -> persistenceService.start(fixture.context(), "executeSql",
                    Map.of("sql", "SELECT 1")), AssistantToolErrorCodeConstant.TOOL_CONTEXT_INVALID);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tool_call WHERE run_id = ?",
                    Integer.class, fixture.runId())).isZero();

            ToolCall toolCall = persistenceService.start(fixture.context(),
                    AssistantToolNameConstant.GET_TICKET_STATUS, Map.of("ticketNo", "T10086"));
            TicketToolQueryResult ticket = ticketQueryService.getByTicketNoWithinSession(
                    fixture.userId(), fixture.sessionId(), "T10086");
            MessageSource forgedSource = sourceFactory.ticket(fixture.context(), toolCall.getId(), ticket);
            forgedSource.setTicketId(UUID.randomUUID());

            assertToolError(() -> persistenceService.succeed(fixture.context(), toolCall.getId(),
                    Map.of("sourceHandle", forgedSource.getId()), List.of(forgedSource)),
                    AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED);
            assertThat(toolCallRepository.findById(toolCall.getId()).orElseThrow().getStatus())
                    .isEqualTo(ToolCallStatusEnum.RUNNING);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message_source WHERE tool_call_id = ?",
                    Integer.class, toolCall.getId())).isZero();

            persistenceService.fail(fixture.context(), toolCall.getId(),
                    AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED);
            assertThat(toolCallRepository.findById(toolCall.getId()).orElseThrow().getStatus())
                    .isEqualTo(ToolCallStatusEnum.FAILED);
        } finally {
            deleteFixture(fixture);
        }
    }

    private void assertTicketSource(UUID sourceHandle, UUID toolCallId, AssistantFixture fixture,
            int citationNo, String expectedStatus) {
        Map<String, Object> source = jdbcTemplate.queryForMap("""
                SELECT source.message_id, source.source_type, source.kb_id, source.chunk_id,
                       source.ticket_id, source.tool_call_id, source.citation_no,
                       source.snapshot ->> 'status' AS snapshot_status
                FROM message_source source
                WHERE source.id = ?
                """, sourceHandle);
        assertThat(source.get("message_id")).isEqualTo(fixture.assistantMessageId());
        assertThat(source.get("source_type")).isEqualTo("TICKET");
        assertThat(source.get("kb_id")).isEqualTo(DEMO_KNOWLEDGE_BASE_ID);
        assertThat(source.get("chunk_id")).isNull();
        assertThat(source.get("ticket_id")).isNotNull();
        assertThat(source.get("tool_call_id")).isEqualTo(toolCallId);
        assertThat(source.get("citation_no")).isEqualTo(citationNo);
        assertThat(source.get("snapshot_status")).isEqualTo(expectedStatus);
    }

    private UUID sourceToolCallId(UUID sourceHandle) {
        return jdbcTemplate.queryForObject(
                "SELECT tool_call_id FROM message_source WHERE id = ?", UUID.class, sourceHandle);
    }

    private void assertToolError(Runnable action, String expectedErrorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AssistantToolException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));
    }

    private AssistantFixture createFixture(boolean includeKnowledgeBaseScope) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = 'support1'", UUID.class);
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        jdbcTemplate.update("""
                -- chat_session：建立 F26 ASSISTANT 工具测试会话，用户身份只由服务端固定。
                INSERT INTO chat_session (id, user_id, title, mode, next_message_seq)
                VALUES (?, ?, 'F26 工具数据库测试', 'ASSISTANT', 3)
                """, sessionId, userId);
        if (includeKnowledgeBaseScope) {
            jdbcTemplate.update("""
                    -- chat_session_kb：固定本轮工具可查询的知识库范围。
                    INSERT INTO chat_session_kb (session_id, kb_id)
                    VALUES (?, ?)
                    """, sessionId, DEMO_KNOWLEDGE_BASE_ID);
        }
        jdbcTemplate.update("""
                -- chat_run：建立可执行工具的运行记录。
                INSERT INTO chat_run (
                    id, session_id, client_request_id, request_hash, status, config_snapshot,
                    metrics, started_at
                ) VALUES (?, ?, ?, ?, 'RUNNING', CAST('{}' AS JSONB), CAST('{}' AS JSONB), CURRENT_TIMESTAMP)
                """, runId, sessionId, UUID.randomUUID(), "a".repeat(64));
        jdbcTemplate.update("""
                -- chat_message：用户消息固定完成，助手消息保持流式状态供工具结果挂接。
                INSERT INTO chat_message (id, run_id, session_id, seq, role, content, status)
                VALUES
                    (?, ?, ?, 1, 'USER', '查询工单 T10086', 'COMPLETED'),
                    (?, ?, ?, 2, 'ASSISTANT', '', 'STREAMING')
                """, userMessageId, runId, sessionId, assistantMessageId, runId, sessionId);
        return new AssistantFixture(userId, sessionId, runId, assistantMessageId);
    }

    private void deleteFixture(AssistantFixture fixture) {
        jdbcTemplate.update("DELETE FROM message_source WHERE message_id = ?", fixture.assistantMessageId());
        jdbcTemplate.update("DELETE FROM tool_call WHERE run_id = ?", fixture.runId());
        jdbcTemplate.update("DELETE FROM chat_message WHERE run_id = ?", fixture.runId());
        jdbcTemplate.update("DELETE FROM chat_run WHERE id = ?", fixture.runId());
        jdbcTemplate.update("DELETE FROM chat_session_kb WHERE session_id = ?", fixture.sessionId());
        jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?", fixture.sessionId());
    }

    private record AssistantFixture(UUID userId, UUID sessionId, UUID runId, UUID assistantMessageId) {

        private AssistantToolContext context() {
            return new AssistantToolContext(runId, sessionId, userId, assistantMessageId);
        }
    }
}
