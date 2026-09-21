package com.haha.supportai.controller.knowledge;

import java.util.List;
import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.entity.dto.UpdateKnowledgeBaseMemberRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseMemberResponse;
import com.haha.supportai.knowledge.service.KnowledgeBaseMemberService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/members")
@RequiredArgsConstructor
@Tag(name = "知识库成员", description = "管理员查询、授权、改权和撤销知识库成员")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class KnowledgeBaseMemberController {

    private final KnowledgeBaseMemberService knowledgeBaseMemberService;

    @Operation(
            operationId = "listKnowledgeBaseMembers",
            summary = "查询知识库成员",
            description = "仅管理员可用；管理权限不要求管理员本人具有该知识库的成员权限")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<KnowledgeBaseMemberResponse>> list(
            @Parameter(description = "知识库标识", required = true) @PathVariable UUID kbId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseMemberService.list(kbId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "upsertKnowledgeBaseMember",
            summary = "新增成员或覆盖成员权限",
            description = "仅管理员可用；同一用户重复提交时幂等覆盖为 READ 或 WRITE",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PutMapping(value = "/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KnowledgeBaseMemberResponse> upsert(
            @Parameter(description = "知识库标识", required = true) @PathVariable UUID kbId,
            @Parameter(description = "目标用户标识", required = true) @PathVariable UUID userId,
            @Valid @RequestBody UpdateKnowledgeBaseMemberRequest updateRequest,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseMemberService.upsert(kbId, userId, updateRequest),
                TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "revokeKnowledgeBaseMember",
            summary = "撤销知识库成员权限",
            description = "仅管理员可用；重复撤权仍返回 204，撤权立即影响后续数据访问",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> revoke(
            @Parameter(description = "知识库标识", required = true) @PathVariable UUID kbId,
            @Parameter(description = "目标用户标识", required = true) @PathVariable UUID userId) {
        knowledgeBaseMemberService.revoke(kbId, userId);
        return ResponseEntity.noContent().build();
    }
}
