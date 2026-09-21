package com.haha.supportai.conversation.service.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.exception.ChatIdempotencyConflictException;
import com.haha.supportai.conversation.exception.ChatRunInProgressException;
import com.haha.supportai.conversation.exception.ChatSessionInvalidRequestException;
import com.haha.supportai.conversation.exception.ChatSessionNotFoundException;
import com.haha.supportai.conversation.exception.ChatSessionScopeRevokedException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.entity.po.ChatSession;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatMessageStatusEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionKnowledgeBaseRepository;
import com.haha.supportai.data.conversation.repository.ChatSessionRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.security.ActiveAccountGuard;
import com.haha.supportai.knowledge.contract.KnowledgeScopeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRunService {

    private static final String REQUEST_HASH_VERSION = "f10-v1";
    private static final String CONFIG_SNAPSHOT = "{\"mode\":\"RAG\"}";
    private static final String EMPTY_JSON = "{}";
    private static final int MAX_QUESTION_LENGTH = 2000;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionKnowledgeBaseRepository chatSessionKnowledgeBaseRepository;
    private final ChatRunRepository chatRunRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ActiveAccountGuard activeAccountGuard;
    private final KnowledgeScopeAccess knowledgeScopeAccess;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedChatRunResponse prepare(UUID sessionId, SubmitMessageCommand command) {
        ValidatedMessage validatedMessage = validateCommand(sessionId, command);
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        ChatSession session = chatSessionRepository.findOwnedByIdForUpdate(sessionId, currentUser.getId())
                .orElseThrow(ChatSessionNotFoundException::new);
        List<UUID> knowledgeBaseIds = chatSessionKnowledgeBaseRepository.listKnowledgeBaseIdsBySessionId(sessionId);
        if (knowledgeBaseIds.isEmpty()
                || !knowledgeScopeAccess.lockAllReadable(currentUser.getId(), knowledgeBaseIds)) {
            throw new ChatSessionScopeRevokedException();
        }

        ChatRun existingRun = chatRunRepository.findBySessionIdAndClientRequestId(sessionId,
                        validatedMessage.clientRequestId())
                .orElse(null);
        if (existingRun != null) {
            if (!existingRun.getRequestHash().equals(validatedMessage.requestHash())) {
                throw new ChatIdempotencyConflictException();
            }
            return responseForExistingRun(existingRun, currentUser.getId());
        }
        if (chatRunRepository.findActiveBySessionId(sessionId).isPresent()) {
            throw new ChatRunInProgressException();
        }

        long firstSequence = session.getNextMessageSeq();
        if (firstSequence < 1 || !chatSessionRepository.advanceNextMessageSeq(sessionId, firstSequence)) {
            throw new ChatRunInProgressException();
        }
        OffsetDateTime now = OffsetDateTime.now();
        ChatRun run = new ChatRun(UUID.randomUUID(), sessionId, validatedMessage.clientRequestId(),
                validatedMessage.requestHash(), ChatRunStatusEnum.QUEUED, CONFIG_SNAPSHOT, null, EMPTY_JSON, null,
                null, null, null, null, now, now);
        chatRunRepository.insert(run);

        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), run.getId(), sessionId, firstSequence,
                ChatMessageRoleEnum.USER, validatedMessage.content(), ChatMessageStatusEnum.COMPLETED, now, now);
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), run.getId(), sessionId, firstSequence + 1,
                ChatMessageRoleEnum.ASSISTANT, "", ChatMessageStatusEnum.STREAMING, now, now);
        chatMessageRepository.insert(userMessage);
        chatMessageRepository.insert(assistantMessage);
        return new PreparedChatRunResponse(run.getId(), sessionId, currentUser.getId(), userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), run.getStatus(), false);
    }

    private PreparedChatRunResponse responseForExistingRun(ChatRun run, UUID userId) {
        ChatMessage userMessage = null;
        ChatMessage assistantMessage = null;
        for (ChatMessage message : chatMessageRepository.listByRunId(run.getId())) {
            if (message.getRole() == ChatMessageRoleEnum.USER) {
                userMessage = message;
            } else if (message.getRole() == ChatMessageRoleEnum.ASSISTANT) {
                assistantMessage = message;
            }
        }
        if (userMessage == null || assistantMessage == null) {
            throw new IllegalStateException("会话运行缺少成对消息记录");
        }
        return new PreparedChatRunResponse(run.getId(), run.getSessionId(), userId, userMessage.getId(),
                assistantMessage.getId(), userMessage.getContent(), run.getStatus(), true);
    }

    private ValidatedMessage validateCommand(UUID sessionId, SubmitMessageCommand command) {
        if (sessionId == null || command == null || command.getClientRequestId() == null || command.getContent() == null) {
            throw new ChatSessionInvalidRequestException();
        }
        String content = command.getContent().trim();
        if (content.isEmpty() || content.length() > MAX_QUESTION_LENGTH) {
            throw new ChatSessionInvalidRequestException();
        }
        return new ValidatedMessage(command.getClientRequestId(), content, requestHash(content));
    }

    private String requestHash(String normalizedContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((REQUEST_HASH_VERSION + "\n" + normalizedContent)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    private record ValidatedMessage(UUID clientRequestId, String content, String requestHash) {
    }
}
