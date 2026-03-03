package com.example.springaidemo.entity;

import lombok.Data;

/**
 * 统一聊天请求实体
 */
@Data
public class ChatRequest {
    /**
     * 模型类型：ollama/openai/silicon-flow
     */
    private String modelType;
    /**
     * 提问内容
     */
    private String message;
    /**
     * 可选：自定义模型名称（覆盖配置文件）
     */
    private String modelName;


    /**
     * 可选
     */
    private String temperature;

    /**
     * 可选
     */
    private String maxTokens;

    /**
     * 是否流式返回：true-流式(SSE)，false-非流式(JSON)
     */
    private boolean stream = false;
}