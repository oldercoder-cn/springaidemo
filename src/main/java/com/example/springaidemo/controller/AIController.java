package com.example.springaidemo.controller;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.entity.ChatResponse;
import com.example.springaidemo.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 优化后的AI控制器（支持流式/非流式返回）
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

    private final org.springframework.context.ApplicationContext applicationContext;

    /**
     * 统一聊天接口（自动适配流式/非流式）
     * - stream=false：返回JSON格式完整结果
     * - stream=true：返回SSE流式结果
     */
    @PostMapping(value = "/chat", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody ChatRequest request) {
        try {
            // 1. 校验模型类型
            String serviceBeanName = switch (request.getModelType().toLowerCase()) {
                case "ollama" -> "ollamaService";
                case "openai" -> "openaiService";
                case "silicon-flow" -> "siliconFlowService";
                default -> throw new IllegalArgumentException("不支持的模型类型：" + request.getModelType());
            };

            // 2. 获取对应服务
            AIService aiService = applicationContext.getBean(serviceBeanName, AIService.class);

            // 3. 根据stream参数选择返回方式
            if (request.isStream()) {
                log.info("【流式调用】模型类型：{}，提问内容：{}", request.getModelType(), request.getMessage());
                return aiService.streamChat(request);
            } else {
                log.info("【非流式调用】模型类型：{}，提问内容：{}", request.getModelType(), request.getMessage());
                return aiService.chat(request);
            }
        } catch (IllegalArgumentException e) {
            log.error("模型类型错误", e);
            if (request.isStream()) {
                // 流式场景返回错误SSE
                SseEmitter emitter = new SseEmitter();
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ex) {
                    log.error("发送流式错误消息失败", ex);
                } finally {
                    emitter.complete();
                }
                return emitter;
            } else {
                // 非流式场景返回错误JSON
                ChatResponse errorResponse = new ChatResponse();
                errorResponse.setContent("错误：" + e.getMessage());
                errorResponse.setModelType(request.getModelType());
                errorResponse.setCostTime(0);
                return errorResponse;
            }
        } catch (Exception e) {
            log.error("聊天接口异常", e);
            if (request.isStream()) {
                SseEmitter emitter = new SseEmitter();
                try {
                    emitter.send(SseEmitter.event().name("error").data("系统异常：" + e.getMessage()));
                } catch (IOException ex) {
                    log.error("发送流式系统异常消息失败", ex);
                } finally {
                    emitter.complete();
                }
                return emitter;
            } else {
                ChatResponse errorResponse = new ChatResponse();
                errorResponse.setContent("系统异常：" + e.getMessage());
                errorResponse.setModelType(request.getModelType());
                errorResponse.setCostTime(0);
                return errorResponse;
            }
        }
    }



}