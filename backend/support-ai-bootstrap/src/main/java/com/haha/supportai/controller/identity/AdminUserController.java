package com.haha.supportai.controller.identity;

import java.util.List;

import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
import com.haha.supportai.identity.entity.vo.UserSummaryResponse;
import com.haha.supportai.identity.service.UserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "账号管理", description = "管理员查询可用于成员授权的账号目录")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class AdminUserController {

    private final UserDirectoryService userDirectoryService;

    @Operation(
            operationId = "listUsersForAdministration",
            summary = "查询账号目录",
            description = "仅管理员可用，返回账号基本信息和启用状态，不返回密码字段")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<UserSummaryResponse>> list(
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(userDirectoryService.listForAdministration(), TraceIdFilter.getTraceId(request));
    }
}
