package com.haha.supportai.controller.knowledge;

import java.net.URI;
import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.document.entity.vo.DocumentVersionUploadResponse;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "文档版本", description = "发布文档新版本并保留历史版本来源")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class DocumentVersionController {

    private final DocumentUploadService documentUploadService;

    @Operation(
            operationId = "uploadKnowledgeDocumentVersion",
            summary = "上传文档新版本",
            description = "需要知识库写权限和当前修订号；相同内容与配置返回 200 并复用当前版本，"
                    + "否则返回 202。新版本完整发布前旧版本继续用于检索",
            requestBody = @RequestBody(required = true, content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(type = "object", requiredProperties = { "file", "expectedRevision" }),
                    schemaProperties = {
                            @SchemaProperty(name = "file", schema = @Schema(type = "string", format = "binary",
                                    description = "UTF-8 Markdown 或 TXT 新版本原文件")),
                            @SchemaProperty(name = "expectedRevision", schema = @Schema(type = "integer",
                                    format = "int64", description = "文档详情中的当前修订号", example = "1"))
                    })),
            responses = {
                    @ApiResponse(responseCode = "200", description = "已复用当前可用版本",
                            useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "202", description = "新版本已登记并进入处理队列",
                            useReturnTypeSchema = true)
            },
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResult<DocumentVersionUploadResponse>> upload(
            @Parameter(description = "文档标识", required = true) @PathVariable UUID documentId,
            @Parameter(hidden = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(hidden = true)
            @RequestParam("expectedRevision") long expectedRevision,
            @Parameter(hidden = true) HttpServletRequest request) {
        DocumentVersionUploadResponse response = documentUploadService
                .uploadVersion(documentId, file, expectedRevision);
        HttpStatus status = response.isReused() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        URI location = URI.create("/api/ingestion-jobs/" + response.getJobId());
        return ResponseEntity.status(status).location(location)
                .body(ApiResult.ok(response, TraceIdFilter.getTraceId(request)));
    }
}
