package com.haha.supportai.controller.knowledge;

import java.util.UUID;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.service.CsrfTokenService;
import com.haha.supportai.knowledge.ingestion.entity.vo.IngestionJobStatusResponse;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobControlService;
import com.haha.supportai.knowledge.ingestion.service.IngestionJobQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion-jobs")
@RequiredArgsConstructor
@Tag(name = "文档处理任务", description = "查看已授权知识库中文档的持久处理状态")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class IngestionJobController {

    private final IngestionJobQueryService ingestionJobQueryService;
    private final IngestionJobControlService ingestionJobControlService;

    @Operation(
            operationId = "getIngestionJob",
            summary = "查询文档处理任务",
            description = "仅知识库成员可查看；不返回租约令牌、执行实例或内部错误详情")
    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<IngestionJobStatusResponse> get(
            @Parameter(description = "入库任务标识", required = true) @PathVariable UUID jobId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(ingestionJobQueryService.get(jobId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "retryIngestionJob",
            summary = "重试失败的文档处理任务",
            description = "仅知识库 WRITE 成员可操作；任务必须失败、未达上限且版本仍可重新取得待处理指针",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(value = "/{jobId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<IngestionJobStatusResponse> retry(
            @Parameter(description = "入库任务标识", required = true) @PathVariable UUID jobId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(ingestionJobControlService.retry(jobId), TraceIdFilter.getTraceId(request));
    }

    @Operation(
            operationId = "cancelIngestionJob",
            summary = "取消文档处理任务",
            description = "仅知识库 WRITE 成员可操作；取消后任务租约立即失效，且不会清理其他版本的待处理指针",
            parameters = @Parameter(
                    name = CsrfTokenService.HEADER_NAME,
                    description = "从 GET /api/auth/csrf 获取的令牌",
                    required = true,
                    in = ParameterIn.HEADER,
                    schema = @Schema(type = "string")))
    @PostMapping(value = "/{jobId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<IngestionJobStatusResponse> cancel(
            @Parameter(description = "入库任务标识", required = true) @PathVariable UUID jobId,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(ingestionJobControlService.cancel(jobId), TraceIdFilter.getTraceId(request));
    }
}
