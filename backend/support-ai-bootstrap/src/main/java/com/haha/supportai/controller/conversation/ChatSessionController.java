package com.haha.supportai.controller.conversation;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.dto.UpdateChatSessionRequest;
import com.haha.supportai.conversation.entity.vo.ChatMessagePageResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionPageResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
import com.haha.supportai.conversation.entity.vo.ChatSessionSummaryResponse;
import com.haha.supportai.conversation.service.session.ChatSessionService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@Tag(name = "会话", description = "个人 RAG、智能助手会话及固定知识库范围")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @Operation(
            operationId = "createChatSession",
            summary = "创建个人问答会话",
            description = "使用当前登录身份创建 RAG 或智能助手会话，并固定非空知识库范围；创建操作不会提交问题或调用模型",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ChatSessionResponse> create(
            @Valid @RequestBody CreateChatSessionRequest createRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(chatSessionService.create(createRequest), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "listChatSessions",
            summary = "分页查询本人会话",
            description = "按最近更新时间倒序返回当前用户的未删除会话；知识范围失效时仅返回受限摘要")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ChatSessionPageResponse> list(
            @Parameter(description = "每页数量，默认 20，最大 100", schema = @Schema(defaultValue = "20",
                    minimum = "1", maximum = "100"))
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "上一页返回的不透明游标")
            @RequestParam(required = false) String cursor,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(chatSessionService.list(limit, cursor), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "updateChatSessionTitle",
            summary = "修改本人会话标题",
            description = "仅允许修改标题，不改变会话模式和固定知识库范围",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PatchMapping(path = "/{sessionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ChatSessionSummaryResponse> updateTitle(
            @Parameter(description = "会话标识", required = true) @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateChatSessionRequest updateRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(chatSessionService.updateTitle(sessionId, updateRequest),
                TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "deleteChatSession",
            summary = "删除本人会话",
            description = "仅逻辑删除没有活动运行的会话；存在活动运行时返回 409，需先取消运行",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "会话标识", required = true) @PathVariable UUID sessionId) {
        chatSessionService.delete(sessionId);
    }

    @Operation(
            operationId = "listChatSessionMessages",
            summary = "分页查询本人会话消息",
            description = "按会话内序号正序返回消息；固定知识范围任一失效时隐藏全部正文和来源")
    @GetMapping(path = "/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ChatMessagePageResponse> listMessages(
            @Parameter(description = "会话标识", required = true) @PathVariable UUID sessionId,
            @Parameter(description = "每页数量，默认 20，最大 100", schema = @Schema(defaultValue = "20",
                    minimum = "1", maximum = "100"))
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "上一页返回的不透明消息序号游标")
            @RequestParam(required = false) String cursor,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(chatSessionService.listMessages(sessionId, limit, cursor),
                TraceIdFilter.getTraceId(request));
    }
}
