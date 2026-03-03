package com.example.springaidemo.service.impl;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.entity.ChatResponse;
import com.example.springaidemo.service.AIService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SiliconFlow 服务实现类（适配 Spring AI 0.8.1，无专属客户端依赖，支持流式/非流式）
 */
@Slf4j
@Service("siliconFlowService")
@RequiredArgsConstructor
public class SiliconFlowServiceImpl implements AIService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 从配置文件读取全局参数
    @Value("${spring.ai.siliconflow.api-key:}")
    private String siliconFlowApiKey;
    @Value("${spring.ai.siliconflow.base-url:https://api.siliconflow.cn/v1}")
    private String siliconFlowBaseUrl;
    @Value("${spring.ai.siliconflow.chat.options.model:deepseek-chat}")
    private String defaultModel;
    @Value("${spring.ai.siliconflow.chat.options.temperature:0.7}")
    private Float defaultTemperature;

    // SSE 超时时间（3分钟）
    private static final long SSE_TIMEOUT = 3 * 60 * 1000L;

    /**
     * 非流式调用（直接调用 SiliconFlow HTTP API）
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String responseContent = "";

        try {
            // 1. 构建请求参数
            String apiKey = siliconFlowApiKey;
            String modelName = request.getModelName() != null ? request.getModelName() : defaultModel;
            Float temperature = defaultTemperature;
            String message = request.getMessage();

            // 校验 API Key
            if (apiKey == null || apiKey.isEmpty()) {
                throw new RuntimeException("未配置 SiliconFlow API Key，请在 application.yml 中设置 spring.ai.siliconflow.api-key");
            }

            // 2. 构建 SiliconFlow API 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("temperature", temperature);
            requestBody.put("stream", false); // 关闭流式

            // 构建消息体
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", message);
            requestBody.put("messages", new Object[]{userMessage});

            // 3. 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);

            // 4. 调用 SiliconFlow API
            String apiUrl = siliconFlowBaseUrl + "/chat/completions";
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(apiUrl, httpEntity, String.class);

            // 5. 解析响应
            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                JsonNode rootNode = objectMapper.readTree(responseEntity.getBody());
                JsonNode choicesNode = rootNode.get("choices").get(0);
                if (choicesNode != null) {
                    responseContent = choicesNode.get("message").get("content").asText();
                }
            } else {
                responseContent = "调用失败：" + responseEntity.getStatusCode() + "，响应：" + responseEntity.getBody();
            }

        } catch (JsonProcessingException e) {
            log.error("SiliconFlow 响应解析失败", e);
            responseContent = "响应解析失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("SiliconFlow 非流式调用失败", e);
            responseContent = "调用失败：" + e.getMessage();
        }

        long costTime = System.currentTimeMillis() - startTime;

        // 构建自定义响应
        ChatResponse response = new ChatResponse();
        response.setContent(responseContent);
        response.setModelType("silicon-flow");
        response.setCostTime(costTime);
        return response;
    }

    /**
     * 流式调用（解析 SiliconFlow SSE 响应，再通过 SseEmitter 推送）
     */
    @Override
    public SseEmitter streamChat(ChatRequest request) {
        // 创建 SSE 发射器
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 超时处理
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data("SiliconFlow 请求超时，请重试"));
                emitter.completeWithError(new RuntimeException("SSE 连接超时"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        // 异常处理
        emitter.onError((e) -> {
            log.error("SiliconFlow 流式调用异常", e);
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
            HttpURLConnection connection = null;
            try {
                // 1. 构建参数
                String apiKey = siliconFlowApiKey;
                String modelName = request.getModelName() != null ? request.getModelName() : defaultModel;
                Float temperature = defaultTemperature;
                String message = request.getMessage();

                // 校验 API Key
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new RuntimeException("未配置 SiliconFlow API Key，请在 application.yml 中设置 spring.ai.siliconflow.api-key");
                }

                // 2. 构建请求体
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("temperature", temperature);
                requestBody.put("stream", true); // 开启流式

                Map<String, String> userMessage = new HashMap<>();
                userMessage.put("role", "user");
                userMessage.put("content", message);
                requestBody.put("messages", new Object[]{userMessage});

                // 3. 建立 HTTP 连接
                URL url = new URL(siliconFlowBaseUrl + "/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setReadTimeout((int) SSE_TIMEOUT);

                // 发送请求体
                String requestJson = objectMapper.writeValueAsString(requestBody);
                connection.getOutputStream().write(requestJson.getBytes(StandardCharsets.UTF_8));

                // 4. 读取流式响应（SSE 格式）
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.equals("data: [DONE]")) {
                        continue; // 跳过空行和结束标记
                    }

                    // 解析 SSE 数据行（格式：data: {"id":"xxx","choices":[{"delta":{"content":"xxx"}}]}）
                    if (line.startsWith("data: ")) {
                        String jsonStr = line.substring(6); // 去掉 "data: " 前缀
                        JsonNode rootNode = objectMapper.readTree(jsonStr);
                        JsonNode deltaNode = rootNode.get("choices").get(0).get("delta");
                        if (deltaNode != null && deltaNode.has("content")) {
                            String content = deltaNode.get("content").asText();
                            if (content != null && !content.isEmpty()) {
                                // 推送流式数据给前端
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(content));
                                // 模拟自然输出延迟
                                TimeUnit.MILLISECONDS.sleep(50);
                            }
                        }
                    }
                }

                // 发送完成标记
                emitter.send(SseEmitter.event().name("complete").data("响应完成"));

            } catch (JsonProcessingException e) {
                log.error("SiliconFlow 流式响应解析失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("响应解析失败：" + e.getMessage()));
                } catch (IOException ex) {
                    log.error("发送解析失败消息失败", ex);
                }
            } catch (Exception e) {
                log.error("SiliconFlow 流式调用失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("调用失败：" + e.getMessage()));
                } catch (IOException ex) {
                    log.error("发送失败消息失败", ex);
                }
            } finally {
                // 关闭连接和发射器
                if (connection != null) {
                    connection.disconnect();
                }
                emitter.complete();
            }
        }).start();

        return emitter;
    }
}