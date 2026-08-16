package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.dto.VideoRegisterRequest;
import com.example.oops.dto.VideoSummaryResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.oops.repository.AnalysisJobRepository;
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
    private final StorageService storageService;
    private final AnalysisServerClient analysisServerClient;

    /**
     * 파일 업로드. (API 명세 2-1)
     * videoId 를 먼저 발급받아야 저장 경로를 videos/{id}/ 로 만들 수 있어서 두 단계로 저장한다.
     */
    @Transactional
    public Video createFromUpload(MultipartFile file, String genre) {
        storageService.validateVideoFile(file);

        Video video = videoRepository.save(Video.builder()
                .sourceType(SourceType.UPLOAD)
                .filename(file.getOriginalFilename())
                .title(file.getOriginalFilename())
                .genre(ContentGenre.fromOrDefault(genre, null))   // null 이면 자동 판별
                .build());

        video.assignStorageKey(storageService.storeVideo(video.getId(), file));
        enforceDurationLimit(video);
        return video;
    }

    /** 유튜브 링크 등록 (명세 외 확장) */
    @Transactional
    public Video createFromUrl(VideoRegisterRequest request) {
        return videoRepository.save(Video.builder()
                .sourceType(SourceType.YOUTUBE)
                .sourceUrl(request.url())
                .title(request.title())
                .channelName(request.channelName())
                .genre(ContentGenre.fromOrDefault(request.genre(), null))
                .build());
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
     * 검수 이력. 최근 등록순 100건. (명세 §3-2)
     *
     * 진행률과 검토 후보 개수를 함께 준다.
     * 영상마다 따로 조회하면 100건에 쿼리가 200번 나가므로 한 번에 모아서 붙인다.
     */
    public List<VideoSummaryResponse> findRecent() {
        List<Video> videos = videoRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 100,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .getContent();

        if (videos.isEmpty()) {
            return List.of();
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

        return videos.stream()
                .map(v -> VideoSummaryResponse.of(
                        v,
                        latestJobs.get(v.getId()),
                        eventCounts.getOrDefault(v.getId(), 0)))
                .toList();
    }

    public Video getEntity(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
    }
}
