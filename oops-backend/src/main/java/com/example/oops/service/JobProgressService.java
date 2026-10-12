package com.example.oops.service;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStage;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import com.example.oops.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 잡의 진행 상태만 따로 갱신한다.
 *
 * 외부 분석에는 트랜잭션이 없다. 시작/진행/실패는 짧은 REQUIRES_NEW로
 * 즉시 확정하고, 완료만 AnalysisResultWriter의 결과 저장에 참여한다.
 *
 * 시작·실패 시에는 영상 목록 API가 쓰는 Video 상태도 함께 갱신한다.
 * 취소는 AnalysisService에서 영상/작업 상태를 함께 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobProgressService {

    private final AnalysisJobRepository jobRepository;
    private final ProgressPublisher progressPublisher;
    private final VideoRepository videoRepository;

    /** 분석 시작. 대상 videoId 를 돌려준다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(Long jobId) {
        AnalysisJob job = lockLatest(jobId).orElse(null);
        if (job == null || job.getStatus() != AnalysisStatus.PENDING) {
            return null;
        }
        job.start();
        job.getVideo().updateStatus(AnalysisStatus.PROCESSING);
        jobRepository.flush();
        publishAfterCommit(job);
        return job.getVideo().getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long jobId, AnalysisStage stage, int progress) {
        update(jobId, stage, progress, stage.getDefaultMessage());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long jobId, AnalysisStage stage, int progress, String message) {
        lockLatest(jobId).ifPresent(job -> {
            if (job.getStatus() != AnalysisStatus.PROCESSING) return;
            job.updateProgress(stage, progress, message);
            jobRepository.flush();
            publishAfterCommit(job);
        });
    }

    // Join the result transaction: completion must not outlive a rolled-back report.
    @Transactional
    public boolean complete(Long jobId) {
        return lockLatest(jobId).map(job -> {
            if (job.getStatus() != AnalysisStatus.PROCESSING) return false;
            job.complete();
            job.getVideo().updateStatus(AnalysisStatus.COMPLETED);
            jobRepository.flush();
            publishAfterCommit(job);
            return true;
        }).orElse(false);
    }

    private void publishAfterCommit(AnalysisJob job) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { progressPublisher.publish(job); }
                    });
        } else progressPublisher.publish(job);
    }

    public void publishStatusAfterCommit(AnalysisJob job) { publishAfterCommit(job); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(Long jobId, String errorCode, String message) {
        return lockLatest(jobId).map(job -> {
            if (!job.getStatus().isRunning()) return false;
            job.fail(errorCode, message);
            job.getVideo().updateStatus(AnalysisStatus.FAILED);
            jobRepository.flush();
            publishAfterCommit(job);
            return true;
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isStopped(Long jobId) {
        return jobRepository.findById(jobId)
                .map(job -> job.getStatus() != AnalysisStatus.PROCESSING
                        || jobRepository.findTopByVideoIdOrderByIdDesc(job.getVideo().getId())
                                .map(latest -> !latest.getId().equals(jobId)).orElse(true))
                .orElse(true);
    }

    /** Every mutating path uses Video -> latest Job, including standalone completion. */
    private java.util.Optional<AnalysisJob> lockLatest(Long jobId) {
        var videoId = jobRepository.findVideoIdById(jobId).orElse(null);
        if (videoId == null || videoRepository.findByIdForUpdate(videoId).isEmpty()) return java.util.Optional.empty();
        return jobRepository.findFirstByVideoIdOrderByIdDesc(videoId).filter(job -> job.getId().equals(jobId));
    }
}
