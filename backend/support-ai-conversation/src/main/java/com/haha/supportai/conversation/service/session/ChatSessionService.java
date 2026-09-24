package com.haha.supportai.conversation.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.UpdateChatSessionRequest;
import com.haha.supportai.conversation.entity.vo.ChatMessageHistoryResponse;
import com.haha.supportai.conversation.entity.vo.ChatMessagePageResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionPageResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionSummaryResponse;
import com.haha.supportai.conversation.entity.vo.RagSourceResponse;
import com.haha.supportai.conversation.exception.ChatSessionActiveRunException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionKnowledgeBaseUnavailableException;
import com.haha.supportai.conversation.exception.ChatSessionNotFoundException;
import com.haha.supportai.conversation.support.StoredMessageSourceResponseFactory;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatSessionModeEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceAvailabilityResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SESSION_CURSOR_PREFIX = "s:";
    private static final String MESSAGE_CURSOR_PREFIX = "m:";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;
    private final ChatRunRepository chatRunRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageSourceRepository messageSourceRepository;
    private final ActiveAccountGuard activeAccountGuard;
    private final KnowledgeScopeAccess knowledgeScopeAccess;
    private final DocumentSourceQueryService documentSourceQueryService;
    private final StoredMessageSourceResponseFactory sourceResponseFactory;

    @Transactional
    public ChatSessionResponse create(CreateChatSessionRequest request) {
        List<UUID> knowledgeBaseIds = normalizeKnowledgeBaseIds(request);
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        if (!knowledgeScopeAccess.lockAllReadable(currentUser.getId(), knowledgeBaseIds)) {
            throw new ChatSessionKnowledgeBaseUnavailableException();
        }

        OffsetDateTime now = OffsetDateTime.now();
        ChatSession session = new ChatSession(UUID.randomUUID(), currentUser.getId(), DEFAULT_TITLE,
                request.getMode(), 1, null, now, now);
        chatSessionRepository.insert(session);
        chatSessionKnowledgeBaseRepository.insert(session.getId(), knowledgeBaseIds, now);
        return ChatSessionResponse.from(session, knowledgeBaseIds);
    }

    @Transactional(readOnly = true)
    public ChatSessionPageResponse list(Integer requestedLimit, String cursor) {
        int limit = normalizeLimit(requestedLimit);
        SessionCursor decodedCursor = decodeSessionCursor(cursor);
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        List<ChatSession> sessions = new ArrayList<>(chatSessionRepository.listOwnedPage(currentUser.getId(),
                decodedCursor == null ? null : decodedCursor.updatedAt(),
                decodedCursor == null ? null : decodedCursor.sessionId(), limit + 1));
        boolean hasMore = sessions.size() > limit;
        if (hasMore) {
            sessions.remove(sessions.size() - 1);
        }

        List<ChatSessionSummaryResponse> items = sessions.stream()
                .map(session -> toSummary(currentUser.getId(), session))
                .toList();
        String nextCursor = hasMore && !sessions.isEmpty()
                ? encodeSessionCursor(sessions.get(sessions.size() - 1))
                : null;
        return new ChatSessionPageResponse(items, nextCursor);
    }

    @Transactional
    public ChatSessionSummaryResponse updateTitle(UUID sessionId, UpdateChatSessionRequest request) {
        String title = normalizeTitle(request);
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        ChatSession session = requireOwnedSessionForUpdate(sessionId, currentUser.getId());
        OffsetDateTime updatedAt = OffsetDateTime.now();
        if (!chatSessionRepository.updateOwnedTitle(sessionId, currentUser.getId(), title, updatedAt)) {
            throw new ChatSessionNotFoundException();
        }
        session.setTitle(title);
        session.setUpdatedAt(updatedAt);
        return toSummary(currentUser.getId(), session);
    }

    @Transactional
    public void delete(UUID sessionId) {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        requireOwnedSessionForUpdate(sessionId, currentUser.getId());
        if (chatRunRepository.findActiveBySessionId(sessionId).isPresent()) {
            throw new ChatSessionActiveRunException();
        }
        if (!chatSessionRepository.softDeleteOwned(sessionId, currentUser.getId(), OffsetDateTime.now())) {
            throw new ChatSessionNotFoundException();
        }
    }

    @Transactional(readOnly = true)
    public ChatMessagePageResponse listMessages(UUID sessionId, Integer requestedLimit, String cursor) {
        int limit = normalizeLimit(requestedLimit);
        long afterSequence = decodeMessageCursor(cursor);
        if (sessionId == null) {
            throw new ChatSessionNotFoundException();
        }
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        ChatSession session = chatSessionRepository.findOwnedById(sessionId, currentUser.getId())
                .orElseThrow(ChatSessionNotFoundException::new);
        List<UUID> knowledgeBaseIds = chatSessionKnowledgeBaseRepository
                .listKnowledgeBaseIdsBySessionId(session.getId());
        boolean contentReadable = knowledgeScopeAccess.allReadable(currentUser.getId(), knowledgeBaseIds);
        List<ChatMessage> messages = new ArrayList<>(chatMessageRepository.listOwnedSessionPage(
                sessionId, currentUser.getId(), afterSequence, limit + 1));
        boolean hasMore = messages.size() > limit;
        if (hasMore) {
            messages.remove(messages.size() - 1);
        }

        List<ChatMessageHistoryResponse> items = messages.stream()
                .map(message -> toHistoryMessage(currentUser.getId(), message, contentReadable))
                .toList();
        String nextCursor = hasMore && !messages.isEmpty()
                ? encodeMessageCursor(messages.get(messages.size() - 1).getSequence())
                : null;
        return new ChatMessagePageResponse(sessionId, contentReadable, items, nextCursor);
    }

    private List<UUID> normalizeKnowledgeBaseIds(CreateChatSessionRequest request) {
        if (request == null || request.getMode() == null || request.getKbIds() == null
                || request.getKbIds().isEmpty() || request.getKbIds().stream().anyMatch(id -> id == null)) {
            throw new ChatSessionInvalidRequestException();
        }
        List<UUID> knowledgeBaseIds = new ArrayList<>(request.getKbIds());
        knowledgeBaseIds.sort(Comparator.naturalOrder());
        for (int index = 1; index < knowledgeBaseIds.size(); index++) {
            if (knowledgeBaseIds.get(index - 1).equals(knowledgeBaseIds.get(index))) {
                throw new ChatSessionInvalidRequestException();
            }
        }
        return List.copyOf(knowledgeBaseIds);
    }

    private ChatSession requireOwnedSessionForUpdate(UUID sessionId, UUID userId) {
        if (sessionId == null) {
            throw new ChatSessionNotFoundException();
        }
        return chatSessionRepository.findOwnedByIdForUpdate(sessionId, userId)
                .orElseThrow(ChatSessionNotFoundException::new);
    }

    private ChatSessionSummaryResponse toSummary(UUID userId, ChatSession session) {
        List<UUID> knowledgeBaseIds = chatSessionKnowledgeBaseRepository
                .listKnowledgeBaseIdsBySessionId(session.getId());
        boolean contentReadable = knowledgeScopeAccess.allReadable(userId, knowledgeBaseIds);
        return ChatSessionSummaryResponse.from(session, knowledgeBaseIds, contentReadable);
    }

    private ChatMessageHistoryResponse toHistoryMessage(UUID userId, ChatMessage message, boolean contentReadable) {
        if (!contentReadable) {
            return ChatMessageHistoryResponse.from(message, null, List.of());
        }
        List<RagSourceResponse> sources = message.getRole() == ChatMessageRoleEnum.ASSISTANT
                ? readableSources(userId, message.getId())
                : List.of();
        return ChatMessageHistoryResponse.from(message, message.getContent(), sources);
    }

    private List<RagSourceResponse> readableSources(UUID userId, UUID assistantMessageId) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (MessageSource source : messageSourceRepository.listByMessageId(assistantMessageId)) {
            if (!source.isCited()) {
                continue;
            }
            try {
                if (source.getSourceType() == MessageSourceTypeEnum.DOCUMENT) {
                    documentSourceQueryService.findAuthorizedSource(userId, source.getChunkId())
                            .map(documentSource -> toSourceResponse(source, documentSource))
                            .ifPresent(sources::add);
                } else if (sourceResponseFactory != null) {
                    sourceResponseFactory.create(userId, source).ifPresent(sources::add);
                }
            } catch (RuntimeException ignored) {
                // 撤权、停用或未知来源必须隐藏，不能返回生成时保存的快照。
            }
        }
        return List.copyOf(sources);
    }

    private RagSourceResponse toSourceResponse(MessageSource stored, DocumentSourceAvailabilityResponse source) {
        return new RagSourceResponse(stored.getCitationNo(), MessageSourceTypeEnum.DOCUMENT, source.getDocumentId(),
                source.getVersionId(), source.getChunkId(), source.getDocumentName(), source.getSection(),
                source.isCurrent(), source.isAvailable(), source.isAvailable()
                        ? "/api/chunks/" + source.getChunkId() + "/source" : null,
                null, null, null, null, stored.getOriginSourceId(), stored.getToolCallId());
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_PAGE_SIZE) {
            throw new ChatSessionInvalidRequestException();
        }
        return requestedLimit;
    }

    private String normalizeTitle(UpdateChatSessionRequest request) {
        if (request == null || request.getTitle() == null) {
            throw new ChatSessionInvalidRequestException();
        }
        String title = request.getTitle().trim();
        if (title.isEmpty() || title.length() > 200) {
            throw new ChatSessionInvalidRequestException();
        }
        return title;
    }

    private String encodeSessionCursor(ChatSession session) {
        String cursor = SESSION_CURSOR_PREFIX + session.getUpdatedAt().toInstant().getEpochSecond() + ":"
                + session.getUpdatedAt().getNano() + ":" + session.getId();
        return encodeCursor(cursor);
    }

    private SessionCursor decodeSessionCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = decodeCursor(cursor);
            String[] parts = decoded.split(":", 4);
            if (parts.length != 4 || !SESSION_CURSOR_PREFIX.substring(0, 1).equals(parts[0])) {
                throw new IllegalArgumentException("会话游标格式错误");
            }
            long epochSecond = Long.parseLong(parts[1]);
            int nano = Integer.parseInt(parts[2]);
            if (nano < 0 || nano > 999_999_999) {
                throw new IllegalArgumentException("会话游标纳秒值错误");
            }
            return new SessionCursor(OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano),
                    ZoneOffset.UTC), UUID.fromString(parts[3]));
        } catch (RuntimeException exception) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private String encodeMessageCursor(long sequence) {
        return encodeCursor(MESSAGE_CURSOR_PREFIX + sequence);
    }

    private long decodeMessageCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = decodeCursor(cursor);
            if (!decoded.startsWith(MESSAGE_CURSOR_PREFIX)) {
                throw new IllegalArgumentException("消息游标格式错误");
            }
            long sequence = Long.parseLong(decoded.substring(MESSAGE_CURSOR_PREFIX.length()));
            if (sequence < 0) {
                throw new IllegalArgumentException("消息游标序号错误");
            }
            return sequence;
        } catch (RuntimeException exception) {
            throw new ChatSessionInvalidRequestException();
        }
    }

    private String encodeCursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record SessionCursor(OffsetDateTime updatedAt, UUID sessionId) {
    }
}
