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

    /**
     * 명세 §11 — STOMP 연결도 REST 와 같은 Origin 을 허용해야 한다.
     * 여기만 빠뜨리면 API 는 되는데 진행률만 안 오는 상태가 된다.
     */
    private final java.util.List<String> allowedOrigins;

    public WebSocketConfig(CorsProperties properties) {
        this.allowedOrigins = properties.originsOrDefault();
    }


    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 10초마다 서로 신호를 주고받는다.
        //
        // **이게 없으면 긴 단계에서 연결이 끊긴다.**
        //
        // 화면 글자 인식(OCR)은 한 번 호출하면 몇 분씩 돌아온다.
        // 그 사이 진행률 메시지가 하나도 안 나가므로 연결이 조용해지는데,
        // 앞단에 리버스 프록시(nginx 등)가 있으면 보통 60초쯤 지나
        // "죽은 연결" 로 보고 끊어버린다.
        //
        // 프론트는 폴링으로 넘어가서 화면은 돌아가지만,
        // 매번 "실시간 연결이 잠시 끊겨..." 가 뜨고 진행률이 뚝뚝 끊긴다.
        //
        // 하트비트를 켜면 할 말이 없어도 10초마다 신호가 오가서
        // 프록시가 연결을 살아 있는 것으로 본다.
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());

        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * 하트비트를 보낼 스레드.
     *
     * SimpleBroker 는 스케줄러를 주지 않으면 하트비트 설정을 무시한다.
     * 설정만 하고 스케줄러를 빼먹으면 조용히 안 도는데,
     * 로그도 안 남아서 "설정했는데 왜 여전히 끊기지" 가 된다.
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
