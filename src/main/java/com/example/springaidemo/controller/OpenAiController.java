package com.example.springaidemo.controller;

import com.example.springaidemo.entity.ChatRequest;
import com.example.springaidemo.service.OpenAIService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 修正后：仅注入 OpenAIService，移除无用导入
 */
@RestController
@RequestMapping("/api/openai")
@RequiredArgsConstructor
public class OpenAiController {

    // 注入接口（而非实现类），Spring 会自动匹配名为 openaiService 的 Bean
    private final OpenAIService openAIService;

    /**
     * 非流式调用
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<String>> chat(@RequestBody ChatRequest request) {
        return openAIService.chat(request);
    }

    /**
     * 流式调用
     */
    @PostMapping(value = "/stream-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest request) {
        return openAIService.streamChat(request);
    }
}