package com.haha.supportai.conversation.support;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.data.assistant.repository.AssistantHistoryRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class StoredMessageSourceResponseFactoryTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID MESSAGE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID CHUNK_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
    private static final UUID TICKET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440004");

    private final DocumentSourceQueryService documentSourceQueryService = mock(DocumentSourceQueryService.class);
    private final AssistantHistoryRepository historyRepository = mock(AssistantHistoryRepository.class);
    private final StoredMessageSourceResponseFactory factory = new StoredMessageSourceResponseFactory(
            documentSourceQueryService, new ObjectMapper(), historyRepository);

    @Test
    void rebuildsDocumentSourceWithCurrentAuthorization() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        MessageSource source = new MessageSource(UUID.randomUUID(), MESSAGE_ID, null, 1, true,
                MessageSourceTypeEnum.DOCUMENT, UUID.randomUUID(), CHUNK_ID, "{}", "{}", OffsetDateTime.now());
        given(documentSourceQueryService.findAuthorizedSource(USER_ID, CHUNK_ID)).willReturn(Optional.of(
                new DocumentSourceAvailabilityResponse(UUID.randomUUID(), documentId, "退款手册", versionId, 3,
                        CHUNK_ID, 2, "退款流程", 8, 20, 80, true, true)));

        var response = factory.create(USER_ID, source);

        assertThat(response).hasValueSatisfying(result -> {
            assertThat(result.getType()).isEqualTo(MessageSourceTypeEnum.DOCUMENT);
            assertThat(result.getDocumentId()).isEqualTo(documentId);
            assertThat(result.getVersionId()).isEqualTo(versionId);
            assertThat(result.getChunkId()).isEqualTo(CHUNK_ID);
            assertThat(result.getTitle()).isEqualTo("退款手册");
            assertThat(result.getSection()).isEqualTo("退款流程");
            assertThat(result.getPreviewPath()).isEqualTo("/api/chunks/" + CHUNK_ID + "/source");
        });
        then(documentSourceQueryService).should().findAuthorizedSource(USER_ID, CHUNK_ID);
    }

    @Test
    void rebuildsTicketSourceFromAuditedSnapshot() {
        MessageSource source = ticketSource("""
                {
                  "ticketNo": "TK-20260923-001",
                  "title": "退款到账延迟",
                  "status": "PROCESSING",
                  "ownerName": "客服一组",
                  "updatedAt": "2026-09-23T09:30:00+08:00",
                  "queriedAt": "2026-09-23T09:40:00+08:00"
                }
                """);

        given(historyRepository.isUsable(source.getId(), null, USER_ID)).willReturn(true);
        var response = factory.create(USER_ID, source);

        assertThat(response).hasValueSatisfying(result -> {
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getType()).isEqualTo(MessageSourceTypeEnum.TICKET);
            assertThat(result.getTitle()).isEqualTo("退款到账延迟");
            assertThat(result.getSection()).isEqualTo("客服一组");
            assertThat(result.getTicketNo()).isEqualTo("TK-20260923-001");
            assertThat(result.getTicketStatus()).isEqualTo("PROCESSING");
            assertThat(result.getTicketUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T09:30:00+08:00"));
            assertThat(result.getTicketQueriedAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T09:40:00+08:00"));
            assertThat(result.isInherited()).isFalse();
            assertThat(result.getPreviewPath()).isNull();
        });
        then(documentSourceQueryService).shouldHaveNoInteractions();
    }

    @Test
    void hidesTicketSourceWhenAuditSnapshotIsIncomplete() {
        MessageSource source = ticketSource("{\"ticketNo\":\"TK-001\",\"status\":\"OPEN\"}");
        given(historyRepository.isUsable(source.getId(), null, USER_ID)).willReturn(true);

        assertThat(factory.create(USER_ID, source)).isEmpty();
        then(documentSourceQueryService).shouldHaveNoInteractions();
    }

    @Test
    void preservesHistoricalQueryTimeAndMarksInheritedTicketAsHistorical() {
        MessageSource source = ticketSource("""
                {"ticketNo":"T10086","status":"OPEN","updatedAt":"2026-09-23T09:30:00Z",
                 "queriedAt":"2026-09-23T09:40:00Z"}
                """);
        source.setOriginSourceId(UUID.randomUUID());
        given(historyRepository.isUsable(source.getId(), null, USER_ID)).willReturn(true);

        assertThat(factory.create(USER_ID, source)).hasValueSatisfying(result -> {
            assertThat(result.isInherited()).isTrue();
            assertThat(result.isCurrent()).isFalse();
            assertThat(result.getOriginSourceId()).isEqualTo(source.getOriginSourceId());
            assertThat(result.getToolCallId()).isEqualTo(source.getToolCallId());
            assertThat(result.getTicketQueriedAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T09:40:00Z"));
        });
    }

    @Test
    void hidesAuditedSnapshotWhenMembershipOrLineageIsNoLongerReadable() {
        MessageSource source = ticketSource("""
                {"ticketNo":"T10086","status":"OPEN","updatedAt":"2026-09-23T09:30:00Z",
                 "queriedAt":"2026-09-23T09:40:00Z"}
                """);
        assertThat(factory.create(USER_ID, source)).isEmpty();
    }

    private MessageSource ticketSource(String snapshot) {
        return new MessageSource(UUID.randomUUID(), MESSAGE_ID, null, 1, true, MessageSourceTypeEnum.TICKET,
                null, null, TICKET_ID, UUID.randomUUID(), null, snapshot, OffsetDateTime.now());
    }
}
