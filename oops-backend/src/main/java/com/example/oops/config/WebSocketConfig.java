package com.example.oops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * API 명세 4. STOMP over WebSocket.
 *
 * 프론트는 /ws 로 연결한 뒤 /topic/videos/{videoId}/progress 를 구독한다.
 * 별도 브로커 없이 내장 SimpleBroker 를 쓴다. MVP 규모에서는 충분하다.
 *
 * 엔드포인트를 두 번 등록하는 이유:
 * SockJS 로만 등록하면 순수 WebSocket 클라이언트가 /ws 에 바로 붙지 못한다.
 * @stomp/stompjs 는 기본이 네이티브 WebSocket 이므로 둘 다 열어둔다.
 *
 *   네이티브 : ws://localhost:8080/ws
 *   SockJS  : http://localhost:8080/ws
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
