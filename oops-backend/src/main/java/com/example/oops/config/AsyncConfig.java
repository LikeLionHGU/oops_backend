package com.example.oops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import lombok.RequiredArgsConstructor;

/**
 * 분석은 오래 걸리므로 요청 스레드와 분리해서 돌린다.
 * 해커톤 규모에서는 이 정도면 충분하고, 트래픽이 커지면 Redis/RabbitMQ 큐로 교체하면 된다.
 */
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

    public static final String ANALYSIS_EXECUTOR = "analysisExecutor";

    private final AnalysisExecutorProperties properties;

    @Bean(name = ANALYSIS_EXECUTOR)
    public ThreadPoolTaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 영상 하나만 분석해도 LLM 호출이 수십 번 나간다.
        // 동시 분석을 늘리면 OpenAI 요청 한도에 먼저 걸린다.
        executor.setCorePoolSize(properties.corePoolSizeOrDefault());
        executor.setMaxPoolSize(properties.maxPoolSizeOrDefault());
        executor.setQueueCapacity(properties.queueCapacityOrDefault());
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }
}
