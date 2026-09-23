package com.haha.supportai.assistant.agent.tool.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.assistant.agent.tool.constant.AssistantToolErrorCodeConstant;
import com.haha.supportai.assistant.agent.tool.entity.dto.AssistantToolContext;
import com.haha.supportai.assistant.agent.tool.exception.AssistantToolException;
import com.haha.supportai.assistant.ticket.entity.dto.TicketToolQueryResult;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalEvidenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolSourceFactory {

    private final ObjectMapper objectMapper;

    public MessageSource document(AssistantToolContext context, UUID toolCallId,
            KnowledgeRetrievalEvidenceResponse evidence) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("section", evidence.getSection());
        locator.put("pageNo", evidence.getPageNo());
        locator.put("startOffset", evidence.getStartOffset());
        locator.put("endOffset", evidence.getEndOffset());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", evidence.getDocumentId());
        snapshot.put("documentName", evidence.getDocumentName());
        snapshot.put("versionId", evidence.getVersionId());
        snapshot.put("versionNo", evidence.getVersionNo());
        snapshot.put("chunkIndex", evidence.getChunkIndex());
        snapshot.put("score", evidence.getScore());
        return new MessageSource(UUID.randomUUID(), context.getAssistantMessageId(), null, 0, false,
                MessageSourceTypeEnum.DOCUMENT, evidence.getKnowledgeBaseId(), evidence.getChunkId(), null,
                toolCallId, serialize(locator), serialize(snapshot), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public MessageSource ticket(AssistantToolContext context, UUID toolCallId, TicketToolQueryResult ticket) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("ticketNo", ticket.getTicketNo());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ticketNo", ticket.getTicketNo());
        snapshot.put("title", ticket.getTitle());
        snapshot.put("status", ticket.getStatus());
        snapshot.put("ownerName", ticket.getOwnerName());
        snapshot.put("updatedAt", ticket.getUpdatedAt());
        snapshot.put("queriedAt", ticket.getQueriedAt());
        return new MessageSource(UUID.randomUUID(), context.getAssistantMessageId(), null, 0, false,
                MessageSourceTypeEnum.TICKET, ticket.getKnowledgeBaseId(), null, ticket.getTicketId(), toolCallId,
                serialize(locator), serialize(snapshot), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssistantToolException(AssistantToolErrorCodeConstant.TOOL_AUDIT_FAILED,
                    "工具来源序列化失败", exception);
        }
    }
}
