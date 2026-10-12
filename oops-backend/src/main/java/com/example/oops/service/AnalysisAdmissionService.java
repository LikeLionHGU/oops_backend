package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.*;
import com.example.oops.repository.AnalysisJobRepository;
import com.example.oops.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Short DB-only admission transactions. Worker health checks belong to the facade. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisAdmissionService {
    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final AnalysisDispatchService dispatch;
    private final JobProgressService progress;

    @Transactional(readOnly = true)
    public void checkEligibility(Long videoId, boolean retry) {
        var video = videos.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
        checkState(video, retry);
    }

    @Transactional
    public AnalysisJob admit(Long videoId, boolean retry) {
        // Re-read under the same Video lock used by cancellation, result commit
        // and retention. The preflight read is never authorization to enqueue.
        var video = videos.findByIdForUpdate(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
        checkState(video, retry);
        if (retry) video.resetReview();
        var job = jobs.save(new AnalysisJob(video));
        video.updateStatus(AnalysisStatus.PENDING);
        try {
            dispatch.submit(job.getId());
        } catch (TaskRejectedException error) {
            log.warn("[pipeline] 분석 실행 자리가 없어 작업을 시작하지 못했습니다. jobId={}", job.getId());
            job.fail("ANALYSIS_QUEUE_FULL", "분석 대기열이 가득 찼거나 종료 중입니다. 잠시 후 다시 시도해 주세요.");
            video.updateStatus(AnalysisStatus.FAILED);
            progress.publishStatusAfterCommit(job);
        }
        return job;
    }

    private void checkState(Video video, boolean retry) {
        if (retry) {
            var last = jobs.findTopByVideoIdOrderByIdDesc(video.getId()).orElse(null);
            if (last != null && !last.getStatus().isRetryable()) {
                throw new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,
                        "실패했거나 취소한 분석만 다시 시도할 수 있습니다. (현재 상태: %s)"
                                .formatted(last.getStatus()));
            }
        }
        if (jobs.existsByVideoIdAndStatusIn(video.getId(),
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS);
        }
    }
}
