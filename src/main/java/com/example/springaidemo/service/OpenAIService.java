package com.example.springaidemo.service;

import com.example.springaidemo.entity.ChatRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OpenAIService {
    Mono<ResponseEntity<String>> chat(ChatRequest request);
    Flux<ServerSentEvent<String>> streamChat(ChatRequest request);
}
