package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.dto.VideoRegisterRequest;
import com.example.oops.dto.VideoHistoryResponse;
import com.example.oops.dto.VideoSummaryResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.oops.repository.AnalysisJobRepository;
import com.example.oops.repository.ReviewActionRepository;
import com.example.oops.repository.RiskFindingRepository;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VideoService {

    private final VideoRepository videoRepository;
    private final AnalysisJobRepository jobRepository;
    private final RiskFindingRepository findingRepository;
    private final ReviewActionRepository actionRepository;
    private final StorageService storageService;
    private final AnalysisServerClient analysisServerClient;

    /**
     * 파일 업로드. (API 명세 2-1)
     * videoId 를 먼저 발급받아야 저장 경로를 videos/{id}/ 로 만들 수 있어서 두 단계로 저장한다.
     */
    @Transactional
    public Video createFromUpload(MultipartFile file, String genre) {
        return createFromUpload(file, genre, null);
    }

    /**
     * 파일 업로드 + (선택) SRT 자막 파일.
     * 자막을 먼저 검사한다. 영상까지 저장한 뒤에 자막 형식 오류로 거절하면 파일이 남는다.
     */
    @Transactional
    public Video createFromUpload(MultipartFile file, String genre, MultipartFile subtitle) {
        String srt = readSubtitle(subtitle);
        storageService.validateVideoFile(file);
        requireAnalysisServer();

        Video video = videoRepository.save(Video.builder()
                .sourceType(SourceType.UPLOAD)
                .filename(file.getOriginalFilename())
                .title(file.getOriginalFilename())
                .genre(ContentGenre.fromOrDefault(genre, null))   // null 이면 자동 판별
                .build());

        video.assignStorageKey(storageService.storeVideo(video.getId(), file));
        video.attachSubtitle(srt);
        enforceDurationLimit(video);
        return video;
    }

    /** 유튜브 링크 등록 (명세 외 확장) */
    @Transactional
    public Video createFromUrl(VideoRegisterRequest request) {
        String srt = validateSubtitleText(request.subtitleSrt());
        requireAnalysisServer();
        Video video = videoRepository.save(Video.builder()
                .sourceType(SourceType.YOUTUBE)
                .sourceUrl(request.url())
                .title(request.title())
                .channelName(request.channelName())
                .genre(ContentGenre.fromOrDefault(request.genre(), null))
                .build());
        video.attachSubtitle(srt);
        return video;
    }

    /**
     * 이미 등록한 영상에 SRT 자막을 붙이거나 바꾼다.
     * 분석은 다시 돌리지 않는다. 재분석(retry)을 부르면 새 자막으로 분석한다.
     *
     * @return 읽은 자막 줄 수
     */
    @Transactional
    public int attachSubtitle(Long videoId, MultipartFile subtitle) {
        Video video = getEntity(videoId);
        // 분석 중에 바꾸면 분석이 끝날 때 영상 행 전체를 다시 쓰면서 새 자막이 옛 값으로 덮인다.
        // 그래서 분석이 끝난 뒤에만 받는다. (retry 와 같은 기준)
        if (jobRepository.existsByVideoIdAndStatusIn(
                videoId, List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS,
                    "분석이 진행 중이라 자막을 바꿀 수 없습니다. 분석이 끝난 뒤 다시 올려 주세요.");
        }
        String srt = readSubtitle(subtitle);
        if (srt == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "자막 파일이 비어 있습니다.");
        }
        video.attachSubtitle(srt);
        return com.example.oops.screentext.SrtParser.parse(srt).size();
    }

    /** 자막 파일 최대 크기. 90분 영상 자막도 보통 200KB 안쪽이다. */
    private static final long MAX_SUBTITLE_BYTES = 5L * 1024 * 1024;

    /** 업로드된 자막 파일을 글로 읽고 형식을 검사한다. 파일이 없으면 null. */
    private String readSubtitle(MultipartFile subtitle) {
        if (subtitle == null || subtitle.isEmpty()) {
            return null;
        }
        if (subtitle.getSize() > MAX_SUBTITLE_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "자막 파일이 너무 큽니다 (최대 5MB).");
        }
        try {
            return validateSubtitleText(com.example.oops.screentext.SrtParser.decode(subtitle.getBytes()));
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "자막 파일을 읽지 못했습니다.");
        }
    }

    /**
     * 자막 글이 SRT 형식인지 본다. 비어 있으면 null (자막 없이 진행).
     * 시간 정보가 하나도 없으면 거절한다. 발언과 시간을 맞출 수 없어서 쓸 수가 없다.
     */
    private String validateSubtitleText(String srt) {
        if (srt == null || srt.isBlank()) {
            return null;
        }
        if (com.example.oops.screentext.SrtParser.parse(srt).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "자막 파일에서 시간 정보가 있는 자막을 찾지 못했습니다. "
                            + "SRT 형식(번호 / 00:00:01,000 --> 00:00:03,000 / 자막)인지 확인하세요.");
        }
        return srt;
    }

    /**
     * 분석 서버가 살아 있는지 **행을 만들기 전에** 확인한다.
     *
     * 예전에는 startAnalysis 에서만 봤다. 그런데 업로드는 트랜잭션이 둘이다.
     *   1) createFromUpload  — video 행 커밋 + 파일 저장
     *   2) startAnalysis     — 여기서 503 WORKER_UNAVAILABLE
     * 그래서 클라이언트는 videoId 없이 503 을 받는데 서버에는 행과 파일이 남았다.
     * 그 영상은 job 이 없어서
     *   · 이력에 영원히 '분석 중' 으로 뜨고
     *   · status·report 는 409 INVALID_ANALYSIS_STATE 를 돌려주고
     *   · 자동 정리 대상에서도 빠진다 (정리 조건이 '끝난 job 이 있는 것' 이다)
     * 백엔드를 먼저 켜는 것이 보통의 순서라서 재현이 쉽다.
     */
    private void requireAnalysisServer() {
        if (!analysisServerClient.isHealthy()) {
            throw new BusinessException(ErrorCode.WORKER_UNAVAILABLE,
                    "분석 서버에 연결할 수 없습니다. oops-analysis 가 실행 중인지 확인하세요.");
        }
    }

    /**
     * 업로드 직후 영상 길이를 확인한다. (명세 §2-1)
     *
     * 예전에는 분석을 다 돌리다가 파이썬이 길이를 재고 실패로 끝냈다.
     * 그러면 STT 비용이 이미 나간 뒤이고, 사용자는 몇 분 기다린 끝에 거절당한다.
     * 여기서 미리 막고 저장한 파일도 지운다.
     *
     * 길이를 못 쟀으면 그냥 통과시킨다.
     * 분석 서버가 잠깐 죽었다는 이유로 업로드를 막으면 더 나쁘다.
     * 그 경우 분석 단계에서 다시 걸린다.
     */
    private void enforceDurationLimit(Video video) {
        analysisServerClient.probe(video)
                .filter(probe -> Boolean.FALSE.equals(probe.withinLimit()))
                .ifPresent(probe -> {
                    storageService.deleteVideoFiles(video.getId());
                    videoRepository.delete(video);

                    int minutes = probe.durationSec() == null ? 0 : probe.durationSec() / 60;
                    int limit = probe.maxDurationSec() == null ? 0 : probe.maxDurationSec() / 60;
                    throw new BusinessException(ErrorCode.MAX_VIDEO_DURATION_EXCEEDED,
                            "영상이 %d분입니다. 최대 %d분까지 분석할 수 있습니다.".formatted(minutes, limit));
                });
    }

    /**
     * 검수 이력. 명세 §4.
     *
     * 화면의 파일명 → 업로드 일자 → 전체 후보 수 → 수정 수 → 상태를
     * 이 응답만으로 그릴 수 있어야 한다.
     *
     * 영상마다 따로 조회하면 20건에 쿼리가 60번 나가므로 한 번에 모아 붙인다.
     */
    public VideoHistoryResponse findHistory(String statusFilter, int page, int size) {
        List<AnalysisStatus> filter = switch (statusFilter == null ? "ALL" : statusFilter.toUpperCase()) {
            case "COMPLETED" -> List.of(AnalysisStatus.COMPLETED);
            case "FAILED" -> List.of(AnalysisStatus.FAILED, AnalysisStatus.CANCELLED);
            default -> List.of();
        };

        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), Math.max(1, Math.min(100, size)),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"));

        var result = filter.isEmpty()
                ? videoRepository.findAll(pageable)
                : videoRepository.findByStatusIn(filter, pageable);

        List<Video> videos = result.getContent();
        if (videos.isEmpty()) {
            return new VideoHistoryResponse(List.of(), result.getNumber(), result.getSize(), 0, 0);
        }

        List<Long> ids = videos.stream().map(Video::getId).toList();

        // id 오름차순이라 뒤에 오는 것이 최신 Job 이다
        Map<Long, AnalysisJob> latestJobs = new HashMap<>();
        for (AnalysisJob job : jobRepository.findByVideoIdInOrderByIdAsc(ids)) {
            latestJobs.put(job.getVideo().getId(), job);
        }

        Map<Long, Integer> eventCounts = new HashMap<>();
        for (Object[] row : findingRepository.countByVideoIds(ids)) {
            eventCounts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        Map<Long, Integer> editedCounts = new HashMap<>();
        for (Object[] row : actionRepository.countEditedByVideoIds(ids)) {
            editedCounts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        List<VideoSummaryResponse> items = videos.stream()
                .map(v -> VideoSummaryResponse.of(
                        v,
                        latestJobs.get(v.getId()),
                        eventCounts.getOrDefault(v.getId(), 0),
                        editedCounts.getOrDefault(v.getId(), 0),
                        v.reviewStatusOrDefault(),
                        v.getReviewedAt()))
                .toList();

        return new VideoHistoryResponse(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public Video getEntity(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
    }
}
