package com.haha.supportai.assistant.ticket.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.assistant.ticket.entity.vo.TicketQueryResponse;
import com.haha.supportai.assistant.ticket.entity.dto.TicketToolQueryResult;
import com.haha.supportai.assistant.ticket.exception.TicketInvalidRequestException;
import com.haha.supportai.assistant.ticket.exception.TicketNotFoundException;
import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.enums.TicketStatusEnum;
import com.haha.supportai.data.assistant.repository.TicketRepository;
import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.entity.po.KnowledgeBase;
import com.haha.supportai.data.knowledge.enums.KbPermissionEnum;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketQueryServiceTest {

    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("25000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    TicketRepository ticketRepository;

    @Mock
    AccessPolicy accessPolicy;

    TicketQueryService ticketQueryService;

    @BeforeEach
    void setUp() {
        ticketQueryService = new TicketQueryService(ticketRepository, accessPolicy);
    }

    @Test
    void normalizesTicketNumberAndReturnsCurrentDatabaseValues() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-23T01:30:00Z");
        Ticket ticket = ticket(TicketStatusEnum.IN_PROGRESS, updatedAt);
        KnowledgeBase knowledgeBase = knowledgeBase();
        KbMember member = new KbMember(KNOWLEDGE_BASE_ID, USER_ID, KbPermissionEnum.READ, USER_ID, updatedAt);
        when(ticketRepository.findByTicketNo("T10086")).thenReturn(Optional.of(ticket));
        when(accessPolicy.requireRead(KNOWLEDGE_BASE_ID))
                .thenReturn(new KnowledgeBaseAccessContext(knowledgeBase, member));

        TicketQueryResponse response = ticketQueryService.getByTicketNo("  t10086  ");

        assertThat(response.getTicketNo()).isEqualTo("T10086");
        assertThat(response.getStatus()).isEqualTo(TicketStatusEnum.IN_PROGRESS);
        assertThat(response.getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(response.getKnowledgeBaseName()).isEqualTo("模拟订单系统工单");
        assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(response.getQueriedAt()).isNotNull();
        verify(ticketRepository).findByTicketNo("T10086");
        verify(accessPolicy).requireRead(KNOWLEDGE_BASE_ID);
    }

    @Test
    void mapsMissingAndUnauthorizedTicketsToTheSameException() {
        when(ticketRepository.findByTicketNo("T99999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ticketQueryService.getByTicketNo("T99999"))
                .isInstanceOf(TicketNotFoundException.class);
        verifyNoInteractions(accessPolicy);

        when(ticketRepository.findByTicketNo("T10086"))
                .thenReturn(Optional.of(ticket(TicketStatusEnum.OPEN, OffsetDateTime.now())));
        when(accessPolicy.requireRead(KNOWLEDGE_BASE_ID)).thenThrow(new KnowledgeBaseNotFoundException());
        assertThatThrownBy(() -> ticketQueryService.getByTicketNo("T10086"))
                .isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void rejectsInvalidTicketNumberBeforeReadingTheDatabase() {
        for (String ticketNo : new String[] { "", " ", "T", "10086", "T/10086", "T10086?debug=true",
                "T12345678901234567890123456789012" }) {
            assertThatThrownBy(() -> ticketQueryService.getByTicketNo(ticketNo))
                    .isInstanceOf(TicketInvalidRequestException.class);
        }
        assertThatThrownBy(() -> ticketQueryService.getByTicketNo(null))
                .isInstanceOf(TicketInvalidRequestException.class);
        verifyNoInteractions(ticketRepository, accessPolicy);
    }

    @Test
    void queriesTicketWithinTrustedSessionScopeWithoutRequestThreadIdentity() {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-23T02:00:00Z");
        Ticket ticket = ticket(TicketStatusEnum.RESOLVED, updatedAt);
        when(ticketRepository.findReadableWithinSession(USER_ID, sessionId, "T10086"))
                .thenReturn(Optional.of(ticket));

        TicketToolQueryResult result = ticketQueryService.getByTicketNoWithinSession(
                USER_ID, sessionId, " t10086 ");

        assertThat(result.getTicketId()).isEqualTo(ticket.getId());
        assertThat(result.getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(result.getStatus()).isEqualTo(TicketStatusEnum.RESOLVED);
        assertThat(result.getUpdatedAt()).isEqualTo(updatedAt);
        verifyNoInteractions(accessPolicy);
    }

    @Test
    void hidesMissingAndOutOfScopeTicketsInTrustedSessionQuery() {
        UUID sessionId = UUID.randomUUID();
        when(ticketRepository.findReadableWithinSession(USER_ID, sessionId, "T10086"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketQueryService.getByTicketNoWithinSession(USER_ID, sessionId, "T10086"))
                .isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> ticketQueryService.getByTicketNoWithinSession(null, sessionId, "T10086"))
                .isInstanceOf(TicketInvalidRequestException.class);
    }

    private Ticket ticket(TicketStatusEnum status, OffsetDateTime updatedAt) {
        return new Ticket(UUID.randomUUID(), "T10086", KNOWLEDGE_BASE_ID, "订单关闭结果未同步",
                "支付成功后的订单状态仍未同步", status, "支持工程师1", updatedAt.minusDays(1), updatedAt);
    }

    private KnowledgeBase knowledgeBase() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-23T00:00:00Z");
        return new KnowledgeBase(KNOWLEDGE_BASE_ID, "模拟订单系统工单", "F25 测试知识库", USER_ID,
                KnowledgeBaseStatusEnum.ACTIVE, 0, now, now);
    }
}
