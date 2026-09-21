package com.haha.supportai.data.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.haha.supportai.data.typehandler.UuidTypeHandler;
import com.haha.supportai.data.typehandler.VectorTypeHandler;
import org.apache.ibatis.type.EnumTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfiguration {

    @Bean
    public ConfigurationCustomizer mybatisConfigurationCustomizer() {
        return configuration -> {
            configuration.setMapUnderscoreToCamelCase(true);
            // UUID 使用 PostgreSQL 原生类型，向量使用显式文本转换，枚举沿用表中的名称值。
            configuration.getTypeAliasRegistry().registerAlias(VectorTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(VectorTypeHandler.class);
            configuration.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        };
    }
}
