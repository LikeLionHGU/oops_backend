package com.example.oops.service;

import com.example.oops.config.OopsProperties;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.Video;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 저장소를 주기적으로 정리한다. 두 가지를 따로 돈다.
 *
 *   1. 원본 미디어 정리 (1시간마다)
 *      분석이 끝나고 24시간 지난 영상의 **원본 파일만** 지운다.
 *      리포트·대본·검토 후보·참고 자료·검수 이력은 그대로 남는다.
 *      사용자는 어제 받은 검수 결과를 계속 볼 수 있고,
 *      우리는 출연자 얼굴과 목소리가 담긴 파일을 오래 들고 있지 않는다.
 *
 *   2. 전체 정리 (매일 새벽 4시, 기본 꺼짐)
 *      영상에 딸린 모든 것을 지운다. 리포트도 사라진다.
 *      oops.storage.retention-days 가 0 이면 돌지 않는다.
 *
 * **1번을 1시간마다 도는 이유가 있다.**
 * 하루 한 번만 돌면 24시간을 지킬 수 없다.
 * 새벽 4시에 도는데 분석이 4시 1분에 끝났다면, 다음 실행 때는 23시간 59분이라
 * 아직 안 지워지고, 그다음 실행까지 기다리면 거의 48시간이 된다.
 * "24시간 안에 지웁니다" 라고 말하려면 확인 간격이 그보다 훨씬 촘촘해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanupScheduler {

    /** 원본을 지우면 안 되는 상태. 분석이 아직 그 파일을 읽고 있다. */
    private static final List<AnalysisStatus> RUNNING =
            List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING);

    private final OopsProperties properties;
    private final VideoRepository videoRepository;
    private final VideoDeletionService deletionService;
    private final StorageService storageService;

    /**
     * 원본 미디어 정리. 매시 정각.
     *
     * 지우는 건 videos/{id}/original.* 하나뿐이다.
     * 프레임 이미지는 검토 후보 카드가 쓰기 때문에 남긴다.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpiredSources() {
        int hours = properties.storage().sourceRetentionHoursOrDefault();
        if (hours <= 0) {
            log.debug("[purge] 원본 보관 시간이 0 이라 정리하지 않습니다.");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusHours(hours);
        List<Video> targets = videoRepository.findSourcePurgeTargets(threshold, RUNNING);
        if (targets.isEmpty()) {
            return;
        }

        long freed = 0;
        int purged = 0;
        for (Video video : targets) {
            try {
                freed += deletionService.purgeSource(video.getId());
                purged++;
            } catch (Exception e) {
                // 한 건 실패해도 나머지는 계속 지운다. 다음 시간에 다시 시도된다.
                log.warn("[purge] videoId={} 원본 삭제 실패: {}", video.getId(), e.getMessage());
            }
        }

        log.info("[purge] 원본 {}개 삭제 ({}MB 확보). 기준: 분석 완료 후 {}시간. 리포트는 유지됩니다.",
                purged, freed / 1024 / 1024, hours);
    }

    /**
     * 전체 정리. 매일 새벽 4시.
     *
     * 이건 리포트까지 지운다. 기본값은 꺼져 있다(retention-days: 0).
     * 로컬 개발에서 테스트 영상이 사라지면 곤란하고,
     * 배포에서도 검수 결과를 며칠 만에 없애는 건 보통 원하는 동작이 아니다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void cleanUp() {
        int retentionDays = properties.storage().retentionDaysOrDefault();
        if (retentionDays <= 0) {
            log.debug("[cleanup] 보관 기간이 설정되지 않아 정리하지 않습니다.");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);

        // 분석이 끝난 것만 지운다. 대기·진행 중인 것을 건드리면 작업이 깨진다.
        List<Video> targets = videoRepository.findByCreatedAtBeforeAndStatusIn(
                threshold, List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED));

        if (targets.isEmpty()) {
            log.info("[cleanup] 정리할 영상이 없습니다. (기준: {}일 이전)", retentionDays);
            return;
        }

        long before = storageService.usedBytes();
        int deleted = 0;
        for (Video video : targets) {
            try {
                deletionService.delete(video.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("[cleanup] videoId={} 정리 실패: {}", video.getId(), e.getMessage());
            }
        }
        long after = storageService.usedBytes();

        log.info("[cleanup] {}개 전체 삭제 완료. 저장소 {}MB → {}MB",
                deleted, before / 1024 / 1024, after / 1024 / 1024);
    }
}
