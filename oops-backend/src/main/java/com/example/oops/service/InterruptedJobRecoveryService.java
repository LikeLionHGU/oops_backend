package com.example.oops.service;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import com.example.oops.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** Single-instance startup repair; does not infer distributed worker ownership. */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterruptedJobRecoveryService {
    private final AnalysisJobRepository jobs;
    private final VideoRepository videos;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recover(Long jobId, LocalDateTime cutoff) {
        var videoId=jobs.findVideoIdById(jobId).orElse(null);
        if (videoId==null) return false;
        var video=videos.findByIdForUpdate(videoId).orElse(null);
        if (video==null) return false;
        var job=jobs.findByIdForUpdate(jobId).orElse(null);
        if (job==null || !job.isRunning() || job.getCreatedAt()==null || !job.getCreatedAt().isBefore(cutoff)
                || (job.getStartedAt()!=null && !job.getStartedAt().isBefore(cutoff))
                || (job.getUpdatedAt()!=null && !job.getUpdatedAt().isBefore(cutoff))) return false;
        var latest=jobs.findTopByVideoIdOrderByIdDesc(videoId).orElse(null);
        if (latest==null) return false;
        job.fail("ANALYSIS_FAILED", "서버가 재시작되어 분석이 중단되었습니다. 다시 시도해 주세요.");
        // A stale older job may block retry/deletion but must not overwrite the
        // newer job's Video state or its retained report/media.
        boolean isLatest=latest.getId().equals(jobId);
        if (isLatest) video.updateStatus(AnalysisStatus.FAILED);
        jobs.flush();
        log.warn("[recovery] 중단 작업 복구 저장 요청 videoId={} jobId={} latest={}", videoId, jobId, isLatest);
        return true;
    }
}
