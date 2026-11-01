package com.example.oops.service;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.AnalysisJobRepository;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Serializes source retention with startAnalysis using the same video row lock. */
@Service
@RequiredArgsConstructor
public class SourceRetentionService {
    /** 원본을 지워도 되는 상태. 취소된 영상도 24시간 뒤 원본을 남기지 않는다. (redesign-v3) */
    public static final List<AnalysisStatus> TERMINAL =
            List.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.CANCELLED);

    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final StorageService storage;

    @Transactional
    public boolean purgeIfExpired(Long videoId, LocalDateTime threshold) {
        var video = videos.findByIdForUpdate(videoId).orElse(null);
        if (video == null || video.getCreatedAt() == null || !video.getCreatedAt().isBefore(threshold)
                || !TERMINAL.contains(video.getStatus()) || video.isSourcePurged()) {
            return false;
        }
        if (jobs.existsByVideoIdAndStatusIn(videoId, List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))) {
            return false;
        }
        // Keep the lock through deletion. A retry cannot become runnable in the gap.
        storage.deleteSourceFile(videoId);
        // redesign-v3: 분석 기록은 텍스트(DB)만 남긴다. 화면 캡처 이미지도 원본과 함께 지운다.
        storage.deleteFrames(videoId);
        // 화면에는 "보관 기간이 지나 원본은 삭제되었습니다"가 보이고 streamUrl 은 null 이 된다.
        video.markSourcePurged(LocalDateTime.now());
        return true;
    }
}
