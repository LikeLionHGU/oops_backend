package com.example.oops.service;

import com.example.oops.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;

/** Disabled by default; opt-in retention cannot be shorter than 24 hours. */
@Slf4j
@Component
public class JobFrameCleanupScheduler {
    private final StorageService storage;
    private final JobFrameRetentionService retention;
    private final int hours;
    private long candidateOffset;

    public JobFrameCleanupScheduler(StorageService storage, JobFrameRetentionService retention,
            @Value("${oops.storage.orphan-frame-retention-hours:0}") int hours) {
        this.storage = storage;
        this.retention = retention;
        this.hours = hours <= 0 ? 0 : Math.max(24, hours);
    }

    @Scheduled(cron = "0 30 * * * *")
    public void cleanUp() {
        if (hours == 0) return;
        Instant markerThreshold = Instant.now().minusSeconds(hours * 3600L);
        LocalDateTime jobThreshold = LocalDateTime.now().minusHours(hours);
        int deleted = 0;
        var candidates = storage.expiredJobFrames(markerThreshold, candidateOffset);
        // Advance past retained folders too, rather than inspecting the same first
        // 1000 referenced jobs forever. Wrap after the end; deletion may shift offsets.
        candidateOffset = candidates.size() < 1000 ? 0 : candidateOffset + candidates.size();
        for (var owner : candidates) {
            try {
                if (retention.purge(owner, markerThreshold, jobThreshold)) deleted++;
            } catch (Exception e) {
                log.warn("[frame-retention] 삭제 실패 videoId={} jobId={}: {}", owner.videoId(), owner.jobId(), e.getMessage());
            }
        }
        if (deleted > 0) log.info("[frame-retention] 미참조 작업 폴더 {}개 정리", deleted);
    }
}
