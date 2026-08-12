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
 * 오래된 영상을 주기적으로 정리한다.
 *
 * 영상 하나에 원본 수십 MB 와 프레임 이미지 수십 장이 쌓인다.
 * 지우는 로직이 없으면 서버 디스크가 조용히 찬다.
 *
 * 보관 기간은 oops.storage.retention-days 로 정한다.
 * 0 이하면 정리하지 않는다. 개발 중에 자기 테스트 영상이 사라지면 곤란하므로
 * 기본값을 짧게 두지 않았다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanupScheduler {

    private final OopsProperties properties;
    private final VideoRepository videoRepository;
    private final VideoDeletionService deletionService;
    private final StorageService storageService;

    /** 매일 새벽 4시 */
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

        log.info("[cleanup] {}개 정리 완료. 저장소 {}MB → {}MB",
                deleted, before / 1024 / 1024, after / 1024 / 1024);
    }
}
