package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.Video;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영상에 딸린 것을 지운다. 두 가지 방식이 있다.
 *
 *   purgeSource()  원본 미디어만. 리포트·대본·후보·참고자료·검수 이력은 남는다.
 *                  분석 완료 24시간 뒤에 자동으로 돈다.
 *   delete()       전부. 사용자가 이 영상을 목록에서 없앨 때만 쓴다.
 *
 * 둘을 나눈 이유는 하나로 두면 잘못된 선택을 강요하기 때문이다.
 * 원본을 오래 들고 있는 건 부담이지만, 검수 결과는 나중에 다시 볼 수 있어야 한다.
 * 예전에는 정리 스케줄러가 delete() 를 불러서 결과까지 같이 날렸다.
 *

 * JPA 의 cascade 를 쓰지 않고 순서를 직접 관리한다.
 * 테이블끼리 참조 관계가 있어서 순서를 틀리면 외래키 제약에 걸린다.
 * risk_finding 과 screen_text 는 video_frame 을 참조하므로
 * 프레임보다 먼저 지워야 한다.
 *
 * DB 행만 지우면 디스크에 영상 파일과 프레임 이미지가 그대로 남는다.
 * 그래서 파일 삭제까지 여기서 함께 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoDeletionService {

    private final VideoRepository videoRepository;
    private final AnalysisJobRepository jobRepository;
    private final AnalysisReportRepository reportRepository;
    private final RiskFindingRepository findingRepository;
    private final ReviewReferenceRepository referenceRepository;
    private final ReviewActionRepository actionRepository;
    private final AnalysisCoverageRepository coverageRepository;
    private final ScreenTextRepository screenTextRepository;
    private final TranscriptSegmentRepository transcriptRepository;
    private final VideoFrameRepository videoFrameRepository;
    private final StorageService storageService;

    @Transactional
    public void delete(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));

        // 분석 중인 영상을 지우면 백그라운드 작업이 사라진 데이터를 건드리게 된다
        boolean running = jobRepository.existsByVideoIdAndStatusIn(
                videoId, List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING));
        if (running) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS,
                    "분석이 진행 중인 영상은 삭제할 수 없습니다. 완료 후 다시 시도하세요.");
        }

        deleteChildren(videoId);
        videoRepository.delete(video);
        storageService.deleteVideoFiles(videoId);

        log.info("[delete] videoId={} 삭제 완료 (DB + 파일)", videoId);
    }

    /**
     * 원본 영상 파일만 지운다. DB 는 그대로 둔다.
     *
     * 지운 뒤에는 sourcePurgedAt 이 찍히고 isStreamable() 이 false 가 된다.
     * 프론트에는 streamUrl 이 null 로 나가므로, 재생 버튼 대신
     * "보관 기간이 지나 원본은 삭제되었습니다" 를 보여주면 된다.
     *
     * 이미 지운 영상은 조용히 넘어간다. 스케줄러가 몇 번을 돌아도 안전하다.
     */
    @Transactional
    public long purgeSource(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));

        if (video.isSourcePurged()) {
            return 0;
        }
        // 분석 중이면 원본이 아직 필요하다. 지우면 작업이 깨진다.
        boolean running = jobRepository.existsByVideoIdAndStatusIn(
                videoId, List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING));
        if (running) {
            log.debug("[purge] videoId={} 분석 중이라 건너뜁니다", videoId);
            return 0;
        }

        long freed = storageService.sourceBytes(videoId);
        storageService.deleteSourceFile(videoId);
        video.markSourcePurged(LocalDateTime.now());

        log.info("[purge] videoId={} 원본 삭제 ({}MB 확보). 리포트·대본·검수 이력은 유지됩니다.",
                videoId, freed / 1024 / 1024);
        return freed;
    }

    /** 참조 순서를 지켜 자식부터 지운다. */
    private void deleteChildren(Long videoId) {
        actionRepository.deleteByVideoId(videoId);       // risk_finding 참조
        referenceRepository.deleteByVideoId(videoId);    // risk_finding 참조
        coverageRepository.deleteByVideoId(videoId);
        findingRepository.deleteByVideoId(videoId);      // video_frame 참조
        screenTextRepository.deleteByVideoId(videoId);   // video_frame 참조
        videoFrameRepository.deleteByVideoId(videoId);
        transcriptRepository.deleteByVideoId(videoId);
        reportRepository.deleteByVideoId(videoId);
        jobRepository.deleteByVideoId(videoId);
    }
}
