package com.haha.supportai.conversation.handler;

import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.exception.ChatSessionActiveRunException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationExceptionHandlerTest {

    @Test
    void activeRunPreventsSessionDeletionWithConflictResponse() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)).thenReturn("trace-f15");

        var response = new ConversationExceptionHandler()
                .handleSessionActiveRun(new ChatSessionActiveRunException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("SESSION_RUN_IN_PROGRESS");
        assertThat(response.getBody().getMessage()).isEqualTo("会话存在进行中的运行，请先取消运行");
    }
}
