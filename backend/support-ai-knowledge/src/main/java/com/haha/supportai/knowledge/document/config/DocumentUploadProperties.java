package com.haha.supportai.knowledge.document.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "support.ai.document.upload")
public class DocumentUploadProperties {

    @NotNull
    private DataSize maxFileSize = DataSize.ofMegabytes(5); // 单个原文件允许的最大大小

    @NotBlank
    private String parserVersion = "plain-text-v1"; // 创建版本时固化的解析器版本

    @NotBlank
    private String chunkerVersion = "token-budget-v1"; // 创建版本时固化的分块器版本

    @Min(1)
    @Max(20)
    private int maxAttempts = 3; // 持久导入任务允许的最大尝试次数
}
