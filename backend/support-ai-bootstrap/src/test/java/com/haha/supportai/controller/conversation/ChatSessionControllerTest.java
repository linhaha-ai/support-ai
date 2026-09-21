package com.haha.supportai.controller.conversation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.UpdateChatSessionRequest;
import com.haha.supportai.conversation.entity.vo.ChatMessagePageResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionPageResponse;
import com.haha.supportai.conversation.service.session.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionControllerTest {

    @Test
    void listAndMessageHistoryKeepServicePaginationContract() {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        ChatSessionService service = mock(ChatSessionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ChatSessionPageResponse sessions = new ChatSessionPageResponse(List.of(), "next-session");
        ChatMessagePageResponse messages = new ChatMessagePageResponse(sessionId, true, List.of(), "next-message");
        when(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)).thenReturn("trace-f15");
        when(service.list(20, "session-cursor")).thenReturn(sessions);
        when(service.listMessages(sessionId, 50, "message-cursor")).thenReturn(messages);
        ChatSessionController controller = new ChatSessionController(service);

        var sessionResult = controller.list(20, "session-cursor", request);
        var messageResult = controller.listMessages(sessionId, 50, "message-cursor", request);

        assertThat(sessionResult.getData()).isSameAs(sessions);
        assertThat(messageResult.getData()).isSameAs(messages);
        assertThat(sessionResult.getTraceId()).isEqualTo("trace-f15");
        verify(service).list(20, "session-cursor");
        verify(service).listMessages(sessionId, 50, "message-cursor");
    }

    @Test
    void updateAndDeleteDelegateOnlySessionManagementFields() throws NoSuchMethodException {
        UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440011");
        ChatSessionService service = mock(ChatSessionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UpdateChatSessionRequest updateRequest = new UpdateChatSessionRequest();
        updateRequest.setTitle("退款会话");
        ChatSessionController controller = new ChatSessionController(service);

        controller.updateTitle(sessionId, updateRequest, request);
        controller.delete(sessionId);

        verify(service).updateTitle(sessionId, updateRequest);
        verify(service).delete(sessionId);
        Method deleteMethod = ChatSessionController.class.getMethod("delete", UUID.class);
        assertThat(deleteMethod.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void f15OperationsExposeStableOpenApiOperationIds() throws NoSuchMethodException {
        assertOperationId("listChatSessions", "list", Integer.class, String.class, HttpServletRequest.class);
        assertOperationId("updateChatSessionTitle", "updateTitle", UUID.class, UpdateChatSessionRequest.class,
                HttpServletRequest.class);
        assertOperationId("deleteChatSession", "delete", UUID.class);
        assertOperationId("listChatSessionMessages", "listMessages", UUID.class, Integer.class, String.class,
                HttpServletRequest.class);
    }

    private void assertOperationId(String expected, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = ChatSessionController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(Operation.class).operationId()).isEqualTo(expected);
    }
}
