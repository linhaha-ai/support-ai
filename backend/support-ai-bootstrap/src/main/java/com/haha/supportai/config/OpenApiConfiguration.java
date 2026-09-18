package com.haha.supportai.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "SupportAI API",
        version = "1.0.0",
        description = "SupportAI 当前已实现的后端接口契约",
        contact = @Contact(name = "SupportAI")))
public class OpenApiConfiguration {
}
