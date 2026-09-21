package com.haha.supportai.controller.conversation;

import java.util.UUID;

import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.stream.ChatMessageStreamService;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageControllerTest {

    @Test
    void newRunReturnsNonBufferedEventStream() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440051");
        UUID runId = UUID.fromString("550e8400-e29b-41d4-a716-446655440052");
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440050");
        UUID userMessageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440053");
        UUID assistantMessageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440054");
        SubmitMessageCommand command = new SubmitMessageCommand(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440055"), "如何排查支付状态？");
        PreparedChatRunResponse preparedRun = new PreparedChatRunResponse(runId, sessionId, userId, userMessageId,
                assistantMessageId, command.getContent(), ChatRunStatusEnum.QUEUED, false);
        ChatRunService chatRunService = mock(ChatRunService.class);
        ChatMessageStreamService streamService = mock(ChatMessageStreamService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SseEmitter emitter = new SseEmitter();
        when(chatRunService.prepare(sessionId, command)).thenReturn(preparedRun);
        when(streamService.open(preparedRun, "trace-test")).thenReturn(emitter);
        when(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)).thenReturn("trace-test");
        ChatMessageController controller = new ChatMessageController(chatRunService, streamService);

        var response = controller.submit(sessionId, command, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, no-transform");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getBody()).isSameAs(emitter);
        verify(streamService).open(preparedRun, "trace-test");
    }
}
