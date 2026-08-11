package com.example.oops.service;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.dto.ProgressMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 진행률을 WebSocket 으로 밀어준다. (API 명세 4-2)
 *
 * 발행 실패가 분석을 멈추면 안 되므로 예외를 삼킨다.
 * 프론트는 WebSocket 이 끊겨도 GET /status 폴링으로 같은 정보를 얻을 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressPublisher {

    private static final String DESTINATION = "/topic/videos/%d/progress";

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(AnalysisJob job) {
        try {
            ProgressMessage message = ProgressMessage.from(job);
            messagingTemplate.convertAndSend(
                    DESTINATION.formatted(job.getVideo().getId()), message);
            log.debug("[ws] videoId={} {}% {}", job.getVideo().getId(),
                    message.progress(), message.stage());
        } catch (Exception e) {
            log.warn("[ws] 진행률 발행 실패 jobId={} : {}", job.getJobKey(), e.getMessage());
        }
    }
}
