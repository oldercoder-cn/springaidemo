package com.example.springaidemo.service.impl;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.entity.ChatResponse;
import com.example.springaidemo.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service("ollamaService")
@RequiredArgsConstructor
public class OllamaServiceImpl implements AIService {

    private final OllamaChatClient ollamaChatClient;
    private static final long SSE_TIMEOUT = 3 * 60 * 1000L;

    /**
     * 非流式调用（仅保留0.8.1支持的参数）
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String responseContent = "";

        try {
            String modelName = request.getModelName() != null ? request.getModelName() : "llama3";
            // 1. 构建消息列表
            UserMessage userMessage = new UserMessage(request.getMessage());
            List<Message> messageList = Collections.singletonList(userMessage);

            // 2. 0.8.1版本OllamaOptions仅配置model和temperature（无stream参数）
            OllamaOptions ollamaOptions = new OllamaOptions();
            ollamaOptions.setModel(modelName); // 仅支持model参数
            ollamaOptions.setTemperature(0.7f); // 仅支持temperature参数

            // 3. 构建Prompt
            Prompt prompt = new Prompt(messageList, ollamaOptions);
            // 非流式调用：call()方法
            org.springframework.ai.chat.ChatResponse aiChatResponse = ollamaChatClient.call(prompt);
            Generation generation = aiChatResponse.getResult();
            if (generation != null) {
                responseContent = generation.getOutput().getContent();
            }
        } catch (Exception e) {
            log.error("Ollama非流式调用失败", e);
            responseContent = "调用失败：" + e.getMessage();
        }

        long costTime = System.currentTimeMillis() - startTime;
        ChatResponse response = new ChatResponse();
        response.setContent(responseContent);
        response.setModelType("ollama");
        response.setCostTime(costTime);
        return response;
    }

    /**
     * 流式调用（0.8.1版本核心：仅通过stream()方法触发流式，无需配置stream参数）
     */
    @Override
    public SseEmitter streamChat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // 超时处理
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data("请求超时，请重试"));
                emitter.completeWithError(new RuntimeException("SSE连接超时"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });
        // 异常处理
        emitter.onError((e) -> {
            log.error("Ollama流式调用异常", e);
            try {
                emitter.send(SseEmitter.event().name("error").data("调用异常：" + e.getMessage()));
            } catch (IOException ex) {
                log.error("发送异常消息失败", ex);
            } finally {
                emitter.complete();
            }
        });

        // 异步处理流式响应
        new Thread(() -> {
            try {
                String modelName = request.getModelName() != null ? request.getModelName() : "llama3";
                // 1. 构建消息列表
                UserMessage userMessage = new UserMessage(request.getMessage());
                List<Message> messageList = Collections.singletonList(userMessage);

                // 2. 0.8.1版本OllamaOptions仅配置基础参数（无stream）
                OllamaOptions ollamaOptions = new OllamaOptions();
                ollamaOptions.setModel(modelName);
                ollamaOptions.setTemperature(0.7f);

                // 3. 构建Prompt
                Prompt prompt = new Prompt(messageList, ollamaOptions);

                // ========== 0.8.1版本流式核心：调用stream()方法即可触发流式 ==========
                // 无需配置stream参数，stream()方法本身就会返回流式响应
                ollamaChatClient.stream(prompt).subscribe(
                        // 处理每一段响应
                        aiChatResponse -> {
                            try {
                                Generation generation = aiChatResponse.getResult();
                                if (generation != null && generation.getOutput() != null) {
                                    String content = generation.getOutput().getContent();
                                    if (content != null && !content.isEmpty()) {
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(content));
                                        // 模拟自然输出延迟
                                        TimeUnit.MILLISECONDS.sleep(50);
                                    }
                                }
                            } catch (IOException | InterruptedException e) {
                                log.error("发送流式数据失败", e);
                                emitter.completeWithError(e);
                            }
                        },
                        // 异常回调
                        e -> {
                            log.error("流式响应异常", e);
                            try {
                                emitter.send(SseEmitter.event().name("error").data("流式响应异常：" + e.getMessage()));
                            } catch (IOException ex) {
                                log.error("发送异常消息失败", ex);
                            } finally {
                                emitter.complete();
                            }
                        },
                        // 完成回调
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("complete").data("响应完成"));
                            } catch (IOException e) {
                                log.error("发送完成标记失败", e);
                            } finally {
                                emitter.complete(); // 关闭SSE连接
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Ollama流式调用初始化失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("初始化失败：" + e.getMessage()));
                } catch (IOException ex) {
                    log.error("发送初始化失败消息失败", ex);
                } finally {
                    emitter.complete();
                }
            }
        }).start();

        return emitter;
    }
}