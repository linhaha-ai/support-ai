package com.haha.supportai.conversation.service.run;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.conversation.exception.ChatRunNotFoundException;
import com.haha.supportai.data.conversation.entity.po.ChatMessage;
import com.haha.supportai.data.conversation.entity.po.ChatRun;
import com.haha.supportai.data.conversation.enums.ChatMessageRoleEnum;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRunControlPersistenceService {

    private final ChatRunRepository chatRunRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 先持久化运行和消息终态，事务提交后上层才能中断内存任务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatRunCancellationResult cancelOwned(UUID runId, UUID userId) {
        ChatRun run = chatRunRepository.findOwnedByIdForUpdate(runId, userId)
                .orElseThrow(ChatRunNotFoundException::new);
        if (!isActive(run.getStatus())) {
            return new ChatRunCancellationResult(run.getId(), run.getStatus(), false);
        }

        ChatMessage assistantMessage = findAssistantMessage(chatMessageRepository.listByRunId(runId));
        OffsetDateTime cancelledAt = OffsetDateTime.now();
        if (!chatRunRepository.cancel(runId, cancelledAt)) {
            throw new IllegalStateException("问答运行取消状态保存失败");
        }
        if (!chatMessageRepository.cancelAssistant(runId, assistantMessage.getId(), cancelledAt)) {
            throw new IllegalStateException("问答助手消息取消状态保存失败");
        }
        return new ChatRunCancellationResult(runId, ChatRunStatusEnum.CANCELLED, true);
    }

    /**
     * 应用启动时只收口本次启动边界前遗留的活跃运行，不自动重放模型调用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverInterrupted(OffsetDateTime startupBoundary) {
        OffsetDateTime recoveredAt = OffsetDateTime.now();
        int interruptedRuns = chatRunRepository.interruptActiveBefore(startupBoundary, recoveredAt);
        if (interruptedRuns == 0) {
            return 0;
        }
        int interruptedMessages = chatMessageRepository.interruptAssistants(recoveredAt);
        if (interruptedMessages != interruptedRuns) {
            throw new IllegalStateException("中断运行与助手消息数量不一致");
        }
        return interruptedRuns;
    }

    private ChatMessage findAssistantMessage(List<ChatMessage> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == ChatMessageRoleEnum.ASSISTANT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("问答运行缺少助手消息"));
    }

    private boolean isActive(ChatRunStatusEnum status) {
        return status == ChatRunStatusEnum.QUEUED || status == ChatRunStatusEnum.RUNNING;
    }
}
