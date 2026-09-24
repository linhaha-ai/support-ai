package com.haha.supportai.conversation.support;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.data.assistant.repository.AssistantHistoryRepository;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StoredMessageSourceResponseFactory {

    private final DocumentSourceQueryService documentSourceQueryService;
    private final ObjectMapper objectMapper;
    private final AssistantHistoryRepository historyRepository;

    @Autowired
    public StoredMessageSourceResponseFactory(DocumentSourceQueryService documentSourceQueryService,
            ObjectMapper objectMapper, AssistantHistoryRepository historyRepository) {
        this.documentSourceQueryService = documentSourceQueryService;
        this.objectMapper = objectMapper;
        this.historyRepository = historyRepository;
    }

    public StoredMessageSourceResponseFactory(DocumentSourceQueryService documentSourceQueryService,
            ObjectMapper objectMapper) {
        this(documentSourceQueryService, objectMapper, null);
    }

    /**
     * 上层复核固定范围，工单再按来源所属用户、历史根调用和当前成员权校验后返回原查询快照。
     */
    public Optional<RagSourceResponse> create(UUID userId, MessageSource source) {
        if (source == null || source.getSourceType() == null || source.getCitationNo() < 1) {
            return Optional.empty();
        }
        if (source.getSourceType() == MessageSourceTypeEnum.DOCUMENT) {
            return documentSourceQueryService.findAuthorizedSource(userId, source.getChunkId())
                    .map(document -> document(source, document));
        }
        return source.getSourceType() == MessageSourceTypeEnum.TICKET && historyRepository != null
                && historyRepository.isUsable(source.getId(), null, userId) ? ticket(source) : Optional.empty();
    }

    private RagSourceResponse document(MessageSource stored, DocumentSourceAvailabilityResponse source) {
        return new RagSourceResponse(stored.getCitationNo(), MessageSourceTypeEnum.DOCUMENT, source.getDocumentId(),
                source.getVersionId(), source.getChunkId(), source.getDocumentName(), source.getSection(),
                source.isCurrent(), source.isAvailable(), source.isAvailable()
                        ? "/api/chunks/" + source.getChunkId() + "/source" : null,
                null, null, null, null, stored.getOriginSourceId(), stored.getToolCallId());
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
            String queriedAt = requiredText(snapshot, "queriedAt");
            return Optional.of(new RagSourceResponse(source.getCitationNo(), MessageSourceTypeEnum.TICKET,
                    null, null, null, title == null ? ticketNo : title, ownerName,
                    source.getOriginSourceId() == null, true, null, ticketNo, status,
                    OffsetDateTime.parse(updatedAt), OffsetDateTime.parse(queriedAt),
                    source.getOriginSourceId(), source.getToolCallId()));
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
