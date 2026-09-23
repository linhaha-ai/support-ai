package com.haha.supportai.controller.assistant;

import com.haha.supportai.assistant.ticket.entity.vo.TicketQueryResponse;
import com.haha.supportai.assistant.ticket.service.TicketQueryService;
import com.haha.supportai.common.entity.vo.ApiResult;
import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.identity.config.IdentityOpenApiConfiguration;
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
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Tag(name = "工单", description = "按知识库成员权限查询模拟工单当前状态")
@SecurityRequirement(name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE)
public class TicketController {

    private final TicketQueryService ticketQueryService;

    @Operation(
            operationId = "getTicketStatus",
            summary = "查询工单当前状态",
            description = "直接读取模拟业务表，不调用模型；不存在和无权访问统一返回 404")
    @GetMapping(value = "/{ticketNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<TicketQueryResponse> get(
            @Parameter(description = "外部业务工单编号，例如 T10086", required = true)
            @PathVariable String ticketNo,
            @Parameter(hidden = true) HttpServletRequest request) {
        return ApiResult.ok(ticketQueryService.getByTicketNo(ticketNo), TraceIdFilter.getTraceId(request));
    }
}
