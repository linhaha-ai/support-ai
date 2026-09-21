package com.haha.supportai.storage.config;

import java.nio.file.Path;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "support.ai.storage")
public class FileStorageProperties {

    private Path root = Path.of("store"); // 本地开发默认原文件根目录
}
