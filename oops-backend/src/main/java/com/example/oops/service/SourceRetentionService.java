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
    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final StorageService storage;

    @Transactional
    public boolean purgeIfExpired(Long videoId, LocalDateTime threshold) {
        var video = videos.findByIdForUpdate(videoId).orElse(null);
        if (video == null || video.getCreatedAt() == null || !video.getCreatedAt().isBefore(threshold)
                || !(video.getStatus() == AnalysisStatus.COMPLETED || video.getStatus() == AnalysisStatus.FAILED)) {
            return false;
        }
        if (jobs.existsByVideoIdAndStatusIn(videoId, List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))) {
            return false;
        }
        // Keep the lock through deletion. A retry cannot become runnable in the gap.
        storage.deleteSourceFile(videoId);
        return true;
    }
}
