package com.haha.supportai.controller.conversation;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.conversation.entity.dto.CreateChatSessionRequest;
import com.haha.supportai.conversation.entity.vo.ChatSessionResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@Tag(name = "会话", description = "个人 RAG 会话及固定知识库范围")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @Operation(
            operationId = "createChatSession",
            summary = "创建个人 RAG 会话",
            description = "使用当前登录身份创建固定且非空的知识库范围；创建操作不会提交问题或调用模型",
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
}
