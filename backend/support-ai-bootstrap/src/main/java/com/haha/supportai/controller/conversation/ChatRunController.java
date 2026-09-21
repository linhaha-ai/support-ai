package com.haha.supportai.controller.conversation;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.ChatRunCancellationResult;
import com.haha.supportai.conversation.entity.vo.ChatRunCancelResponse;
import com.haha.supportai.conversation.entity.vo.ChatRunStatusResponse;
import com.haha.supportai.conversation.service.run.ChatRunControlService;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/runs")
@RequiredArgsConstructor
@Tag(name = "问答运行", description = "查询本人问答运行持久化状态并停止生成")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class ChatRunController {

    private final ChatRunControlService chatRunControlService;

    @Operation(
            operationId = "getChatRunStatus",
            summary = "查询问答运行状态",
            description = "返回本人运行的持久化状态、部分或完整回答、当前仍授权的引用来源及脱敏错误")
    @GetMapping(value = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ChatRunStatusResponse> getStatus(
            @Parameter(description = "问答运行标识", required = true) @PathVariable UUID runId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(chatRunControlService.getStatus(runId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "cancelChatRun",
            summary = "停止问答运行",
            description = "当前知识库权限失效后仍可停止本人运行；已进入终态的运行不会被取消",
            responses = {
                    @ApiResponse(responseCode = "200", description = "运行已处于终态",
                            useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "202", description = "已持久化取消并请求中断执行",
                            useReturnTypeSchema = true)
            },
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(value = "/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResult<ChatRunCancelResponse>> cancel(
            @Parameter(description = "问答运行标识", required = true) @PathVariable UUID runId,
            @Parameter(hidden = true) HttpServletRequest request) {
        ChatRunCancellationResult result = chatRunControlService.cancel(runId);
        HttpStatus status = result.isNewlyCancelled() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResult.ok(ChatRunCancelResponse.from(result), TraceIdFilter.getTraceId(request)));
    }
}
