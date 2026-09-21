package com.haha.supportai.knowledge.document.handler;

import com.haha.supportai.common.filter.TraceIdFilter;
import com.haha.supportai.knowledge.document.parsing.exception.DocumentReadFailedException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DocumentExceptionHandlerTest {

    private final MockMvc mvc = standaloneSetup(new FailingDocumentController())
            .setControllerAdvice(new DocumentExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void documentReadFailureMapsTo503WithSanitizedMessageAndTraceId() throws Exception {
        MvcResult result = mvc.perform(get("/test/document-content"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_READ_FAILED"))
                .andExpect(jsonPath("$.message").value("原文件暂时无法读取"))
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("storage-secret"))))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("\"traceId\":\"")
                .doesNotContain("storage-secret");
        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER_NAME)).isNotBlank();
    }

    @RestController
    static class FailingDocumentController {

        @GetMapping("/test/document-content")
        String readContent() {
            throw new DocumentReadFailedException(new IllegalStateException("storage-secret"));
        }
    }
}
