package com.haha.supportai.controller.knowledge;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.entity.dto.KnowledgeRetrievalTestRequest;
import com.haha.supportai.knowledge.entity.vo.KnowledgeRetrievalTestResponse;
import com.haha.supportai.knowledge.service.retrieval.KnowledgeRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/retrieval-tests")
@RequiredArgsConstructor
@Tag(name = "知识库检索", description = "验证当前知识库内已授权且已发布文档的向量检索结果")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class KnowledgeRetrievalController {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    @Operation(
            operationId = "testKnowledgeBaseRetrieval",
            summary = "调试知识库检索",
            description = "仅具有 WRITE 权限的知识库成员可调用；只检索当前模型空间内已发布、未删除文档的证据",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KnowledgeRetrievalTestResponse> test(
            @Parameter(description = "知识库标识", required = true) @PathVariable UUID kbId,
            @Valid @RequestBody KnowledgeRetrievalTestRequest request,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {
        KnowledgeRetrievalTestResponse response = knowledgeRetrievalService.testWritableKnowledgeBase(kbId, request);
        return ApiResult.ok(response, TraceIdFilter.getTraceId(servletRequest));
    }
}
