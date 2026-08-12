package com.example.oops.service;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.dto.VideoRegisterRequest;
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
    private final StorageService storageService;

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

    public Video getEntity(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_NOT_FOUND));
    }
}
