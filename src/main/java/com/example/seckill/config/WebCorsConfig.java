package com.example.seckill.config;

import org.springframework.context.annotation.Configuration; // 配置类
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer; //配置接口的方法
import org.springframework.web.servlet.config.annotation.CorsRegistry; // 跨域规则


@Configuration
public class WebCorsConfig implements WebMvcConfigurer{
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
