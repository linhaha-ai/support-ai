package com.haha.supportai.assistant.ticket.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.haha.supportai.assistant.ticket.entity.dto.TicketToolQueryResult;
import com.haha.supportai.assistant.ticket.entity.vo.TicketQueryResponse;
import com.haha.supportai.assistant.ticket.exception.TicketInvalidRequestException;
import com.haha.supportai.assistant.ticket.exception.TicketNotFoundException;
import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.repository.TicketRepository;
import com.haha.supportai.knowledge.access.AccessPolicy;
import com.haha.supportai.knowledge.entity.dto.KnowledgeBaseAccessContext;
import com.haha.supportai.knowledge.exception.KnowledgeBaseAccessDeniedException;
import com.haha.supportai.knowledge.exception.KnowledgeBaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketQueryService {

    private static final Pattern TICKET_NO_PATTERN = Pattern.compile("[A-Z][A-Z0-9-]{1,31}");

    private final TicketRepository ticketRepository;
    private final AccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public TicketQueryResponse getByTicketNo(String rawTicketNo) {
        String ticketNo = normalizeTicketNo(rawTicketNo);
        Ticket ticket = ticketRepository.findByTicketNo(ticketNo)
                .orElseThrow(TicketNotFoundException::new);
        KnowledgeBaseAccessContext accessContext;
        try {
            accessContext = accessPolicy.requireRead(ticket.getKnowledgeBaseId());
        } catch (KnowledgeBaseNotFoundException | KnowledgeBaseAccessDeniedException exception) {
            throw new TicketNotFoundException();
        }
        return TicketQueryResponse.from(ticket, accessContext.getKnowledgeBase(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 工具查询只使用服务端准备的用户和会话标识，数据层同时验证固定范围与实时成员权。
     */
    @Transactional(readOnly = true)
    public TicketToolQueryResult getByTicketNoWithinSession(UUID userId, UUID sessionId, String rawTicketNo) {
        if (userId == null || sessionId == null) {
            throw new TicketInvalidRequestException();
        }
        String ticketNo = normalizeTicketNo(rawTicketNo);
        Ticket ticket = ticketRepository.findReadableWithinSession(userId, sessionId, ticketNo)
                .orElseThrow(TicketNotFoundException::new);
        return TicketToolQueryResult.from(ticket, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String normalizeTicketNo(String rawTicketNo) {
        if (rawTicketNo == null) {
            throw new TicketInvalidRequestException();
        }
        String ticketNo = rawTicketNo.trim().toUpperCase(Locale.ROOT);
        if (!TICKET_NO_PATTERN.matcher(ticketNo).matches()) {
            throw new TicketInvalidRequestException();
        }
        return ticketNo;
    }
}
