package com.example.springaidemo.entity;

import lombok.Data;

/**
 * 统一聊天响应实体
 */
@Data
public class ChatResponse {
    /**
     * 模型返回内容
     */
    private String content;
    /**
     * 使用的模型类型
     */
    private String modelType;
    /**
     * 响应耗时（毫秒）
     */
    private long costTime;
}
