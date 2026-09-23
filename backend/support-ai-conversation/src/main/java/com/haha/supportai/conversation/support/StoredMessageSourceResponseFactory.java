package com.haha.supportai.conversation.support;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoredMessageSourceResponseFactory {

    private final DocumentSourceQueryService documentSourceQueryService;
    private final ObjectMapper objectMapper;

    /**
     * 文档来源按当前权限重新读取；工单来源只在上层确认会话固定范围仍全部可读后解析审计快照。
     */
    public Optional<RagSourceResponse> create(UUID userId, MessageSource source) {
        if (source == null || source.getSourceType() == null || source.getCitationNo() < 1) {
            return Optional.empty();
        }
        if (source.getSourceType() == MessageSourceTypeEnum.DOCUMENT) {
            return documentSourceQueryService.findAuthorizedSource(userId, source.getChunkId())
                    .map(document -> document(source.getCitationNo(), document));
        }
        return source.getSourceType() == MessageSourceTypeEnum.TICKET ? ticket(source) : Optional.empty();
    }

    private RagSourceResponse document(int citationNumber, DocumentSourceAvailabilityResponse source) {
        return new RagSourceResponse(citationNumber, MessageSourceTypeEnum.DOCUMENT, source.getDocumentId(),
                source.getVersionId(), source.getChunkId(), source.getDocumentName(), source.getSection(),
                source.isCurrent(), source.isAvailable(), source.isAvailable()
                        ? "/api/chunks/" + source.getChunkId() + "/source" : null);
    }

    private Optional<RagSourceResponse> ticket(MessageSource source) {
        if (source.getTicketId() == null || source.getSnapshot() == null) {
            return Optional.empty();
        }
        try {
            JsonNode snapshot = objectMapper.readTree(source.getSnapshot());
            String ticketNo = requiredText(snapshot, "ticketNo");
            String status = requiredText(snapshot, "status");
            String title = optionalText(snapshot, "title");
            String ownerName = optionalText(snapshot, "ownerName");
            String updatedAt = requiredText(snapshot, "updatedAt");
            return Optional.of(new RagSourceResponse(source.getCitationNo(), MessageSourceTypeEnum.TICKET,
                    null, null, null, title == null ? ticketNo : title, ownerName, true, true, null,
                    ticketNo, status, OffsetDateTime.parse(updatedAt)));
        } catch (JsonProcessingException | RuntimeException exception) {
            // 工具审计快照损坏时隐藏该来源，禁止返回不完整或未经验证的字段。
            return Optional.empty();
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("工具来源快照缺少字段");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()
                ? null : value.textValue();
    }
}
