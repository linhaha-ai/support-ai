package com.haha.supportai.controller.knowledge;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.document.entity.vo.DocumentChunkPageResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentContentResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentDetailResponse;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionPageResponse;
import com.haha.supportai.knowledge.document.service.DocumentQueryService;
import com.haha.supportai.knowledge.service.document.DocumentDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "文档检查", description = "查看已授权文档的版本、原文及分块结果")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class DocumentQueryController {

    private final DocumentQueryService documentQueryService;
    private final DocumentDeletionService documentDeletionService;

    @Operation(
            operationId = "getKnowledgeDocument",
            summary = "查询文档详情",
            description = "仅知识库成员可读取；管理员不会自动绕过成员授权")
    @GetMapping(value = "/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<DocumentDetailResponse> get(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(documentQueryService.get(documentId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "listKnowledgeDocumentVersions",
            summary = "查询文档版本",
            description = "按版本号倒序返回不透明游标分页结果，包含每个版本的脱敏任务摘要")
    @GetMapping(value = "/{documentId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<DocumentVersionPageResponse> listVersions(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId,
            @Parameter(description = "每页数量，默认 20，最大 100", example = "20")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "上一页响应返回的不透明游标")
            @RequestParam(required = false) String cursor,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(documentQueryService.listVersions(documentId, limit, cursor),
                TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "listKnowledgeDocumentChunks",
            summary = "查询文档版本分块",
            description = "按分块序号正序返回定位信息与正文；嵌入尚未落库时正文为空")
    @GetMapping(value = "/{documentId}/versions/{versionId}/chunks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<DocumentChunkPageResponse> listChunks(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId,
            @Parameter(description = "文档版本标识", required = true) @PathVariable UUID versionId,
            @Parameter(description = "每页数量，默认 50，最大 100", example = "50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "上一页响应返回的不透明游标")
            @RequestParam(required = false) String cursor,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(documentQueryService.listChunks(documentId, versionId, limit, cursor),
                TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "getKnowledgeDocumentContent",
            summary = "读取文档版本原文",
            description = "从受控存储读取并校验大小、SHA-256 与 UTF-8 编码，不返回内部存储键")
    @GetMapping(value = "/{documentId}/versions/{versionId}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<DocumentContentResponse> getContent(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId,
            @Parameter(description = "文档版本标识", required = true) @PathVariable UUID versionId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(documentQueryService.getContent(documentId, versionId),
                TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "deleteKnowledgeDocument",
            summary = "删除文档",
            description = "仅 WRITE 成员可删除；文档立即退出检索，原文件和正文由后台清理任务回收",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 /api/auth/csrf 获取的 CSRF 令牌",
                    required = true,
                    in = ParameterIn.HEADER))
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Void> delete(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId) {
        documentDeletionService.delete(documentId);
        return ResponseEntity.accepted().build();
    }
}
