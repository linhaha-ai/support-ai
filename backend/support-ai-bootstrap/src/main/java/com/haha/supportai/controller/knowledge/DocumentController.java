package com.haha.supportai.controller.knowledge;

import java.net.URI;
import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.document.entity.vo.DocumentUploadReceiptResponse;
import com.haha.supportai.knowledge.document.service.DocumentUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/documents")
@RequiredArgsConstructor
@Tag(name = "知识库文档", description = "向具有写权限的知识库提交业务文档")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class DocumentController {

    private final DocumentUploadService documentUploadService;

    @Operation(
            operationId = "uploadKnowledgeDocument",
            summary = "上传知识库文档",
            description = "仅接受 UTF-8 Markdown/TXT；202 仅表示已接收并进入队列，文档尚不可用于问答",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ApiResult<DocumentUploadReceiptResponse>> upload(
            @Parameter(description = "知识库标识", required = true) @PathVariable UUID kbId,
            @Parameter(description = "UTF-8 Markdown 或 TXT 原文件", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "可选文档展示名称，最多 255 个字符")
            @RequestParam(value = "displayName", required = false) String displayName,
            @Parameter(hidden = true) HttpServletRequest request) {
        DocumentUploadReceiptResponse receipt = documentUploadService.upload(kbId, file, displayName);
        URI location = URI.create("/api/ingestion-jobs/" + receipt.getJobId());
        return ResponseEntity.accepted().location(location)
                .body(ApiResult.ok(receipt, TraceIdFilter.getTraceId(request)));
    }
}
