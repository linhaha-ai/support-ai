package com.haha.supportai.controller.knowledge;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.entity.dto.CreateKnowledgeBaseRequest;
import com.haha.supportai.knowledge.entity.dto.UpdateKnowledgeBaseRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseDetailResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseManagementResponse;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseSummaryResponse;
import com.haha.supportai.knowledge.service.KnowledgeBaseService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "知识库授权列表、创建、详情与元数据维护")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(
            operationId = "listAccessibleKnowledgeBases",
            summary = "查询可访问知识库",
            description = "仅返回当前用户具有成员权限且处于启用状态的知识库")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<KnowledgeBaseSummaryResponse>> list(
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseService.listAccessible(), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "createKnowledgeBase",
            summary = "创建知识库",
            description = "仅管理员可创建，创建者会在同一事务中获得 WRITE 成员权限",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<KnowledgeBaseDetailResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest createRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseService.create(createRequest), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "getKnowledgeBase",
            summary = "查询知识库详情",
            description = "仅允许当前具有成员权限的用户读取启用知识库，管理员和负责人不会自动绕过成员授权")
    @GetMapping(value = "/{kbId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KnowledgeBaseDetailResponse> detail(
            @Parameter(description = "知识库标识", required = true)
            @PathVariable UUID kbId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseService.getAccessible(kbId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "updateKnowledgeBase",
            summary = "更新知识库元数据",
            description = "仅管理员可更新；请求必须携带当前 revision，过期版本返回 409",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PatchMapping(value = "/{kbId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KnowledgeBaseManagementResponse> update(
            @Parameter(description = "知识库标识", required = true)
            @PathVariable UUID kbId,
            @Valid @RequestBody UpdateKnowledgeBaseRequest updateRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseService.update(kbId, updateRequest),
                TraceIdFilter.getTraceId(request));
    }
}
