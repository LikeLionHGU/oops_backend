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

import java.util.List;

/**
 * 영상 하나와 거기 딸린 모든 것을 지운다.
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
