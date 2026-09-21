package com.haha.supportai.controller.knowledge;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chunks")
@RequiredArgsConstructor
@Tag(name = "回答来源", description = "查看当前用户仍有权读取的文档来源正文")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class DocumentSourceController {

    private final DocumentSourceQueryService documentSourceQueryService;

    @Operation(
            operationId = "getDocumentChunkSource",
            summary = "读取回答的文档来源",
            description = "允许读取仍可访问的 READY 历史版本，并标明是否为当前生效版本；撤权或文档删除后不可预览")
    @GetMapping(value = "/{chunkId}/source", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<DocumentSourceResponse> getSource(
            @Parameter(description = "来源分块标识", required = true) @PathVariable UUID chunkId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(documentSourceQueryService.getReadableSource(chunkId), TraceIdFilter.getTraceId(request));
    }
}
