package com.haha.supportai.common.handler;

import java.sql.SQLException;

import com.haha.supportai.common.entity.vo.ApiError;
import com.haha.supportai.common.filter.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    @Test
    void databaseFailureReturnsSanitizedStructuredError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.ATTRIBUTE_NAME, "trace-f30-database");

        var response = new ApiExceptionHandler().handleUnexpected(
                new SQLException("connection failed: password=should-not-leak"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError error = response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.getMessage()).isEqualTo("服务暂时不可用");
        assertThat(error.getTraceId()).isEqualTo("trace-f30-database");
    }
}
