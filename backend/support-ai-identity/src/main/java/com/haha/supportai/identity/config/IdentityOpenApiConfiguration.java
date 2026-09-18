package com.haha.supportai.identity.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(
        name = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = IdentityOpenApiConfiguration.SA_TOKEN_COOKIE_NAME,
        description = "登录成功后由服务端写入的 HttpOnly Sa-Token Cookie")
public class IdentityOpenApiConfiguration {

    public static final String SA_TOKEN_COOKIE = "SaTokenCookie";
    public static final String SA_TOKEN_COOKIE_NAME = "support-ai-token";
}
