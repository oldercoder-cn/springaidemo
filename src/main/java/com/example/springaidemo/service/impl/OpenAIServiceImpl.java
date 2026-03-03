package com.example.springaidemo.service.impl;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.service.OpenAIService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全版本兼容：用 statusCode().value() 替代 rawStatusCode()
 * 解决：无法解析 ClientResponse 中的 rawStatusCode 方法
 */
@Slf4j
@Service("openaiService")
@RequiredArgsConstructor
public class OpenAIServiceImpl implements OpenAIService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // 配置项
    @Value("${spring.ai.openai.base-url:https://api.siliconflow.cn}")
    private String baseUrl;
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;
    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String defaultModel;
    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private Float defaultTemperature;
    @Value("${spring.ai.openai.chat.options.max-tokens:2048}")
    private Integer defaultMaxTokens;

    @Override
    public Mono<ResponseEntity<String>> chat(ChatRequest request) {
        // 1. 基础校验（避免空指针）
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body("API Key 未配置"));
        }
        String message = request.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body("消息内容不能为空"));
        }

        // 2. 构造请求体（严格对齐 OpenAI 规范）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", request.getModelName() != null ? request.getModelName().trim() : defaultModel);
        requestBody.put("temperature", request.getTemperature() != null ? request.getTemperature() : defaultTemperature);
        requestBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : defaultMaxTokens);
        requestBody.put("stream", false);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", message.trim())));

        // 3. 构建 WebClient（无 Spring AI 依赖）
        WebClient webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // 4. 发送请求（核心：全版本兼容写法）
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                // 4xx 错误处理（兼容所有版本：用 statusCode().value()）
                .onStatus(
                        code -> code.value() >= 400 && code.value() < 500,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errorBody -> {
                                    // ========== 核心修复：替换 rawStatusCode() ==========
                                    int status = clientResponse.statusCode().value();
                                    return new IllegalArgumentException(String.format("客户端错误 %d：%s", status, errorBody));
                                })
                                .flatMap(Mono::error)
                )
                // 5xx 错误处理（同理）
                .onStatus(
                        code -> code.value() >= 500 && code.value() < 600,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errorBody -> {
                                    // ========== 核心修复：替换 rawStatusCode() ==========
                                    int status = clientResponse.statusCode().value();
                                    return new RuntimeException(String.format("服务端错误 %d：%s", status, errorBody));
                                })
                                .flatMap(Mono::error)
                )
                // 正常响应
                .bodyToMono(String.class)
                .map(this::parseOpenAIResponse)
                // 全局异常捕获（兜底）
                .onErrorResume(e -> {
                    log.error("非流式调用失败", e);
                    return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
                })
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).filter(e -> e instanceof RuntimeException));
    }

    @Override
    public Flux<ServerSentEvent<String>> streamChat(ChatRequest request) {
        // 1. 基础校验
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Flux.just(buildSseEvent("error", "API Key 未配置"));
        }
        String message = request.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return Flux.just(buildSseEvent("error", "消息内容不能为空"));
        }

        // 2. 构造请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", request.getModelName() != null ? request.getModelName().trim() : defaultModel);
        requestBody.put("temperature", defaultTemperature);
        requestBody.put("max_tokens", defaultMaxTokens);
        requestBody.put("stream", true);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", message.trim())));

        // 3. 构建 WebClient
        WebClient webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // 4. 发送流式请求（全版本兼容）
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                // 所有错误处理（兼容写法）
                .onStatus(
                        code -> code.value() >= 400,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errorBody -> {
                                    // ========== 核心修复：替换 rawStatusCode() ==========
                                    int status = clientResponse.statusCode().value();
                                    return new RuntimeException(String.format("调用失败 %d：%s", status, errorBody));
                                })
                                .flatMap(Mono::error)
                )
                // 步骤1：过滤无效行（提前排除空行/[DONE]）
                .bodyToFlux(String.class)
                .filter(line -> {
                    if (line.isEmpty() || line.equals("data: [DONE]")) {
                        return false;
                    }
                    return line.startsWith("data: ");
                })
                // 步骤2：处理有效行（确保无 null 返回）
                .map(line -> {
                    try {
                        String jsonStr = line.substring(6);
                        JsonNode root = objectMapper.readTree(jsonStr);
                        JsonNode delta = root.get("choices").get(0).get("delta");

                        if (delta != null && delta.has("content")) {
                            String content = delta.get("content").asText();
                            if (content != null && !content.isEmpty()) {
                                return buildSseEvent("message", content);
                            }
                        }
                    } catch (Exception e) {
                        log.error("解析流式数据失败：{}", line, e);
                    }
                    // 兜底返回 empty 事件（而非 null）
                    return buildSseEvent("empty", "");
                })
                // 步骤3：过滤空事件
                .filter(event -> !"empty".equals(event.event()) && event.data() != null && !event.data().isEmpty())
                // 步骤4：添加结束标记
                .concatWith(Flux.just(buildSseEvent("complete", "[DONE]")))
                // 流式异常处理
                .onErrorResume(e -> {
                    log.error("流式调用失败", e);
                    return Flux.just(buildSseEvent("error", e.getMessage()));
                });
    }

    /**
     * 辅助方法：解析 OpenAI 响应
     */
    private ResponseEntity<String> parseOpenAIResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.get("choices").get(0).get("message").get("content").asText().trim();
            return ResponseEntity.ok(content);
        } catch (Exception e) {
            log.error("解析响应失败", e);
            return ResponseEntity.ok(responseBody);
        }
    }

    /**
     * 辅助方法：构建 SSE 事件（确保永不返回 null）
     */
    private ServerSentEvent<String> buildSseEvent(String event, String data) {
        String safeData = data == null ? "" : data;
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(safeData)
                .build();
    }
}