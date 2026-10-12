package com.example.oops.service;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * Single-instance startup repair for jobs left by the previous JVM.
 * Reads only IDs, then rechecks each job in a separate locked transaction.
 * Never resumes analysis, deletes results, or assumes distributed ownership.
 * Disable when multiple Spring instances share the same database.
 */
@Slf4j
@Component
public class StaleJobRecovery {
    private final AnalysisJobRepository jobRepository;
    private final InterruptedJobRecoveryService recoveryService;
    private final boolean enabled;
    // Exclude jobs created/started/updated by this process before readiness.
    private final java.time.LocalDateTime cutoff=java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()),
            java.time.ZoneId.systemDefault());

    public StaleJobRecovery(AnalysisJobRepository jobRepository, InterruptedJobRecoveryService recoveryService,
            @Value("${oops.analysis.startup-recovery-enabled:true}") boolean enabled) {
        this.jobRepository=jobRepository;
        this.recoveryService=recoveryService;
        this.enabled=enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!enabled) {
            log.info("[recovery] 시작 시 작업 복구가 비활성화되어 있습니다.");
            return;
        }
        List<Long> stale = jobRepository.findRecoveryCandidateIds(cutoff,
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING));

        if (stale.isEmpty()) {
            return;
        }

        int recovered=0;
        for (Long jobId : stale) {
            try {
                if (recoveryService.recover(jobId, cutoff)) recovered++;
            } catch (Exception e) {
                log.error("[recovery] 복구 실패 jobId={}, 나머지 작업은 계속 확인합니다.", jobId, e);
            }
        }

        log.warn("[recovery] 서버 재시작으로 중단된 분석 {}건을 정리했습니다. "
                + "최신 작업이 중단된 영상은 다시 시도할 수 있습니다.", recovered);
    }
}
