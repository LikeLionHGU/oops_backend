package com.example.oops.service;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStage;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 잡의 진행 상태만 따로 갱신한다.
 *
 * 왜 별도 서비스인가:
 * 분석은 몇 분씩 걸리고 전체가 하나의 트랜잭션 안에서 돈다.
 * 그 안에서 job.progress 를 바꿔봐야 커밋 전까지는 다른 트랜잭션에 보이지 않는다.
 * 그래서 GET /status 로 폴링하면 계속 0% 만 나오다가 끝나는 순간 100% 로 점프한다.
 *
 * 여기서는 REQUIRES_NEW 로 매번 새 트랜잭션을 열고 즉시 커밋한다.
 * 그래야 폴링과 WebSocket 이 같은 값을 실시간으로 본다.
 *
 * 주의: 이 클래스는 AnalysisJob 만 건드린다.
 * Video 는 바깥 트랜잭션이 관리하므로 여기서 손대면 서로 덮어쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobProgressService {

    private final AnalysisJobRepository jobRepository;
    private final ProgressPublisher progressPublisher;

    /** 분석 시작. 대상 videoId 를 돌려준다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(Long jobId) {
        AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
        job.start();
        jobRepository.flush();
        progressPublisher.publish(job);
        return job.getVideo().getId();
    }

    /**
     * 취소를 눌렀는지. 파이프라인이 단계 사이에서 확인한다.
     *
     * 파이프라인 본체는 하나의 긴 트랜잭션이라, 그 안에서 job 을 다시 읽어도
     * 트랜잭션 시작 시점의 값이 보인다. REQUIRES_NEW 로 새로 열어야
     * 취소 API 가 커밋한 상태가 보인다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isCancelled(Long jobId) {
        return jobRepository.findById(jobId)
                .map(job -> job.getStatus() == AnalysisStatus.CANCELLED)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long jobId, AnalysisStage stage, int progress) {
        update(jobId, stage, progress, stage.getDefaultMessage());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long jobId, AnalysisStage stage, int progress, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.updateProgress(stage, progress, message);
            jobRepository.flush();
            progressPublisher.publish(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.complete();
            jobRepository.flush();
            progressPublisher.publish(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long jobId, String errorCode, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.fail(errorCode, message);
            jobRepository.flush();
            progressPublisher.publish(job);
        });
    }
}
