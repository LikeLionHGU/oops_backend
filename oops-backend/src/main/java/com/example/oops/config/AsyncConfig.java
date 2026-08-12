package com.example.oops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 분석은 오래 걸리므로 요청 스레드와 분리해서 돌린다.
 * 해커톤 규모에서는 이 정도면 충분하고, 트래픽이 커지면 Redis/RabbitMQ 큐로 교체하면 된다.
 */
@Configuration
public class AsyncConfig {

    public static final String ANALYSIS_EXECUTOR = "analysisExecutor";

    @Bean(name = ANALYSIS_EXECUTOR)
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 영상 하나만 분석해도 LLM 호출이 수십 번 나간다.
        // 동시 분석을 늘리면 OpenAI 요청 한도에 먼저 걸린다.
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }
}
