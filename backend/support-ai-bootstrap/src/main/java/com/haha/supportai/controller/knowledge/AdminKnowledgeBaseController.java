package com.haha.supportai.controller.knowledge;

import java.util.List;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.data.knowledge.enums.KnowledgeBaseStatusEnum;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.knowledge.entity.vo.KnowledgeBaseManagementResponse;
import com.haha.supportai.knowledge.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "管理员查询全部知识库元数据")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class AdminKnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @Operation(
            operationId = "listManagedKnowledgeBases",
            summary = "查询全部知识库管理元数据",
            description = "仅管理员可用，可查看停用知识库和管理员本人没有成员权限的知识库")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<KnowledgeBaseManagementResponse>> list(
            @Parameter(description = "可选的知识库状态筛选")
            @RequestParam(required = false) KnowledgeBaseStatusEnum status,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(knowledgeBaseService.listAll(status), TraceIdFilter.getTraceId(request));
    }
}
