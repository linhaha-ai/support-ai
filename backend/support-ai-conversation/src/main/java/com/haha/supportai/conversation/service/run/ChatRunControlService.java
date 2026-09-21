package com.haha.supportai.conversation.service.run;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.entity.dto.ChatModelUsage;
import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.conversation.entity.dto.RagMetrics;
import com.haha.supportai.conversation.entity.vo.ChatRunStatusResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRunControlService {

    private static final String EMPTY_JSON = "{}";

    private final ChatRunRepository chatRunRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final MessageSourceRepository messageSourceRepository;
    private final ActiveAccountGuard activeAccountGuard;
    private final KnowledgeScopeAccess knowledgeScopeAccess;
    private final DocumentSourceQueryService documentSourceQueryService;
    private final ChatRunControlPersistenceService persistenceService;
    private final ChatRunExecutionRegistry executionRegistry;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ChatRunStatusResponse getStatus(UUID runId) {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        ChatRun run = chatRunRepository.findOwnedById(runId, currentUser.getId())
                .orElseThrow(ChatRunNotFoundException::new);
        MessagePair messages = findMessagePair(chatMessageRepository.listByRunId(runId));
        List<UUID> knowledgeBaseIds = sessionKnowledgeBaseRepository
                .listKnowledgeBaseIdsBySessionId(run.getSessionId());
        boolean contentReadable = knowledgeScopeAccess.allReadable(currentUser.getId(), knowledgeBaseIds);
        String answer = contentReadable ? messages.assistant().getContent() : null;
        List<RagSourceResponse> sources = contentReadable
                ? readableSources(currentUser.getId(), messages.assistant().getId())
                : List.of();
        RagMetrics metrics = deserialize(run.getMetrics(), RagMetrics.class);
        if (!contentReadable && metrics != null) {
            metrics = metrics.withoutQueryRewriteText();
        }
        return new ChatRunStatusResponse(run.getId(), run.getSessionId(), messages.user().getId(),
                messages.assistant().getId(), run.getStatus(), messages.assistant().getStatus(), answer,
                contentReadable, sources, deserialize(run.getUsage(), ChatModelUsage.class),
                metrics, run.getErrorCode(), run.getErrorDetail(),
                isActive(run.getStatus()), run.getCancelRequestedAt(), run.getStartedAt(), run.getFinishedAt());
    }

    public ChatRunCancellationResult cancel(UUID runId) {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        ChatRunCancellationResult result = persistenceService.cancelOwned(runId, currentUser.getId());
        if (result.isNewlyCancelled()) {
            executionRegistry.cancel(runId);
        }
        return result;
    }

    private MessagePair findMessagePair(List<ChatMessage> messages) {
        ChatMessage userMessage = null;
        ChatMessage assistantMessage = null;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessageRoleEnum.USER) {
                userMessage = message;
            } else if (message.getRole() == ChatMessageRoleEnum.ASSISTANT) {
                assistantMessage = message;
            }
        }
        if (userMessage == null || assistantMessage == null) {
            throw new IllegalStateException("问答运行缺少成对消息记录");
        }
        return new MessagePair(userMessage, assistantMessage);
    }

    private List<RagSourceResponse> readableSources(UUID userId, UUID assistantMessageId) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (MessageSource source : messageSourceRepository.listByMessageId(assistantMessageId)) {
            if (!source.isCited() || source.getSourceType() != MessageSourceTypeEnum.DOCUMENT) {
                continue;
            }
            try {
                documentSourceQueryService.findReadableSource(userId, source.getChunkId())
                        .map(documentSource -> toSourceResponse(source.getCitationNo(), documentSource))
                        .ifPresent(sources::add);
            } catch (RuntimeException ignored) {
                // 当前无法确认读取权限的来源必须隐藏，不能退回生成时保存的快照。
            }
        }
        return List.copyOf(sources);
    }

    private RagSourceResponse toSourceResponse(int citationNumber, DocumentSourceResponse source) {
        return new RagSourceResponse(citationNumber, MessageSourceTypeEnum.DOCUMENT, source.getDocumentId(),
                source.getVersionId(), source.getChunkId(), source.getDocumentName(), source.getSection(),
                source.isCurrent(), "/api/chunks/" + source.getChunkId() + "/source");
    }

    private <T> T deserialize(String json, Class<T> type) {
        if (json == null || json.isBlank() || EMPTY_JSON.equals(json.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            // 历史元数据损坏不应阻断运行状态查询，也不能向客户端暴露原始内容。
            return null;
        }
    }

    private boolean isActive(ChatRunStatusEnum status) {
        return status == ChatRunStatusEnum.QUEUED || status == ChatRunStatusEnum.RUNNING;
    }

    private record MessagePair(ChatMessage user, ChatMessage assistant) {
    }
}
