package com.example.springaidemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 注册 RestTemplate 和 ObjectMapper Bean，解决 SiliconFlowServiceImpl 依赖注入失败问题
 */
@Configuration // 标记为配置类，Spring 会自动扫描并注册其中的 Bean
public class RestTemplateConfig {

    /**
     * 注册 RestTemplate Bean，供 SiliconFlowServiceImpl 注入使用
     */
    @Bean // 将方法返回的实例注册为 Spring Bean
    public RestTemplate restTemplate() {
        // 基础版本直接返回 RestTemplate 实例，满足核心需求
        return new RestTemplate();
    }

    /**
     * 注册 ObjectMapper Bean，用于 JSON 解析（SiliconFlow 接口响应解析）
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}