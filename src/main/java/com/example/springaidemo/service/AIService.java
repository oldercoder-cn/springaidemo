package com.example.springaidemo.service;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.entity.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一AI模型服务接口
 */
public interface AIService {
    ChatResponse chat(ChatRequest request);
    /**
     * 流式聊天（SSE逐段返回结果）
     */
    SseEmitter streamChat(ChatRequest request);
}
