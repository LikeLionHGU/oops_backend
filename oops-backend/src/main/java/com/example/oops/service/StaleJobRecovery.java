package com.example.oops.service;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 서버가 재시작될 때 중간에 끊긴 분석을 정리한다.
 *
 * 분석은 메모리 위의 스레드에서 돈다. 서버가 죽으면 그 스레드도 사라지는데
 * DB 의 job 은 PROCESSING 인 채로 남는다. 그러면:
 *
 *   - GET /report 는 영원히 409 ANALYSIS_NOT_COMPLETED
 *   - 진행률은 그 자리에서 멈춘 채 끝나지 않음
 *   - 재시도를 누르면 409 ANALYSIS_IN_PROGRESS (이미 도는 줄 알고 막는다)
 *   - 영상 삭제도 막힌다
 *
 * 사용자 입장에서는 손쓸 방법이 없다. 그 영상은 영영 못 본다.
 *
 * 이어서 돌리지는 않는다. 어디까지 했는지 모르는 상태로 재개하면
 * 중복 저장이나 반쯤 분석된 결과가 나온다.
 * 실패로 명확히 끝내고 재시도할 수 있게 열어주는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobRecovery {

    private static final String MESSAGE =
            "서버가 재시작되어 분석이 중단되었습니다. 다시 시도해 주세요.";

    private final AnalysisJobRepository jobRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        List<AnalysisJob> stale = jobRepository.findByStatusIn(
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING));

        if (stale.isEmpty()) {
            return;
        }

        for (AnalysisJob job : stale) {
            job.fail("ANALYSIS_FAILED", MESSAGE);
            job.getVideo().updateStatus(AnalysisStatus.FAILED);
            log.warn("[recovery] 중단된 분석을 실패로 정리합니다. videoId={} jobId={} 마지막 단계={} {}%",
                    job.getVideo().getId(), job.getJobKey(), job.getStage(), job.getProgress());
        }

        log.warn("[recovery] 서버 재시작으로 중단된 분석 {}건을 정리했습니다. "
                + "해당 영상은 재시도 버튼으로 다시 분석할 수 있습니다.", stale.size());
    }
}
