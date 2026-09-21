package com.haha.supportai.controller.conversation;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.conversation.entity.dto.SubmitMessageCommand;
import com.haha.supportai.conversation.entity.vo.ChatRunReuseResponse;
import com.haha.supportai.conversation.service.run.ChatRunService;
import com.haha.supportai.conversation.service.stream.ChatMessageStreamService;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/messages")
@RequiredArgsConstructor
@Tag(name = "会话消息", description = "提交个人 RAG 会话消息并逐步接收回答")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class ChatMessageController {

    private static final String STREAM_CACHE_CONTROL = "no-cache, no-store, no-transform";

    private final ChatRunService chatRunService;
    private final ChatMessageStreamService chatMessageStreamService;

    @Operation(
            operationId = "submitChatMessage",
            summary = "提交消息并逐步接收回答",
            description = "新请求返回 text/event-stream，并依次发送 start、delta、sources、done 或 error；"
                    + "命中相同幂等请求时返回 application/json，且不会再次调用模型",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<?> submit(
            @Parameter(description = "本人会话标识", required = true) @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitMessageCommand command,
            @Parameter(hidden = true) HttpServletRequest request) {
        String traceId = TraceIdFilter.getTraceId(request);
        PreparedChatRunResponse preparedRun = chatRunService.prepare(sessionId, command);
        if (preparedRun.isReused()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResult.ok(ChatRunReuseResponse.from(preparedRun), traceId));
        }

        SseEmitter emitter = chatMessageStreamService.open(preparedRun, traceId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, STREAM_CACHE_CONTROL)
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
