package com.haha.supportai.data.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import org.apache.ibatis.type.EnumTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfiguration {

    @Bean
    public ConfigurationCustomizer mybatisConfigurationCustomizer() {
        return configuration -> {
            configuration.setMapUnderscoreToCamelCase(true);
            // UUID 使用 PostgreSQL 原生类型，枚举沿用既有表中的名称值。
            configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
            configuration.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        };
    }
}
