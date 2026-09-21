package com.haha.supportai.conversation.service.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.conversation.entity.po.MessageSource;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.ChatRunRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RagRunPersistenceService {

    private final ChatRunRepository chatRunRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageSourceRepository messageSourceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(PreparedChatRunResponse preparedRun) {
        if (preparedRun == null || !markRunning(preparedRun, OffsetDateTime.now())) {
            throw new IllegalStateException("问答运行无法进入执行状态");
        }
    }

    /**
     * 运行进入 RUNNING 后再以独立事务登记全部入模证据，提交后才能调用模型。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerEvidence(PreparedChatRunResponse preparedRun, List<MessageSource> sources) {
        if (preparedRun == null || sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("问答证据不能为空");
        }
        if (messageSourceRepository.insertAll(preparedRun.getRunId(), sources) != sources.size()) {
            throw new IllegalStateException("问答证据在登记前已失效");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(UUID runId, UUID assistantMessageId, String delta) {
        if (delta == null || delta.isEmpty()
                || !chatMessageRepository.appendAssistantContent(runId, assistantMessageId, delta,
                        OffsetDateTime.now())) {
            throw new IllegalStateException("助手回答增量保存失败");
        }
    }

    /**
     * 独立事务先登记全部入模证据，事务提交后编排服务才能调用模型。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startWithEvidence(PreparedChatRunResponse preparedRun, List<MessageSource> sources) {
        if (preparedRun == null || sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("问答证据不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!markRunning(preparedRun, now)) {
            throw new IllegalStateException("问答运行无法进入执行状态");
        }
        if (messageSourceRepository.insertAll(preparedRun.getRunId(), sources) != sources.size()) {
            throw new IllegalStateException("问答证据在登记前已失效");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeWithoutEvidence(PreparedChatRunResponse preparedRun, String answer, String metrics) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!markRunning(preparedRun, now)
                || !chatMessageRepository.completeAssistant(preparedRun.getRunId(),
                        preparedRun.getAssistantMessageId(), answer, now)
                || !chatRunRepository.complete(preparedRun.getRunId(), null, metrics, now)) {
            throw new IllegalStateException("无证据回答状态保存失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID runId, UUID assistantMessageId, String answer, List<Integer> citedSourceNumbers,
            String metrics) {
        complete(runId, assistantMessageId, answer, citedSourceNumbers, null, metrics);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID runId, UUID assistantMessageId, String answer, List<Integer> citedSourceNumbers,
            String usage, String metrics) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!chatMessageRepository.completeAssistant(runId, assistantMessageId, answer, now)) {
            throw new IllegalStateException("助手回答保存失败");
        }
        if (messageSourceRepository.markCited(assistantMessageId, citedSourceNumbers) != citedSourceNumbers.size()) {
            throw new IllegalStateException("回答引用状态保存失败");
        }
        if (!chatRunRepository.complete(runId, usage, metrics, now)) {
            throw new IllegalStateException("问答运行完成状态保存失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, UUID assistantMessageId, String errorCode, String errorDetail) {
        fail(runId, assistantMessageId, null, errorCode, errorDetail, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, UUID assistantMessageId, String partialContent, String errorCode, String errorDetail,
            String usage, String metrics) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!chatMessageRepository.failAssistant(runId, assistantMessageId, partialContent, now)
                || !chatRunRepository.fail(runId, errorCode, errorDetail, usage, metrics, now)) {
            throw new IllegalStateException("问答失败状态保存失败");
        }
    }

    private boolean markRunning(PreparedChatRunResponse preparedRun, OffsetDateTime startedAt) {
        return chatRunRepository.markRunning(preparedRun.getRunId(), preparedRun.getSessionId(),
                preparedRun.getUserMessageId(), preparedRun.getAssistantMessageId(), preparedRun.getQuestion(),
                startedAt);
    }
}
