package com.example.oops.service;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** Removes only acknowledged, expired, terminal, unreferenced OCR job folders. */
@Service
@RequiredArgsConstructor
public class JobFrameRetentionService {
    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final VideoFrameRepository frames;
    private final StorageService storage;

    @Transactional(rollbackFor = IOException.class)
    public boolean purge(StorageService.JobFrames owner, Instant markerThreshold, LocalDateTime jobThreshold) throws IOException {
        var video = videos.findByIdForUpdate(owner.videoId()).orElse(null);
        if (video == null || jobs.existsByVideoIdAndStatusIn(owner.videoId(),
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))) return false;
        var job = jobs.findById(owner.jobId()).orElse(null);
        if (job == null || !job.getVideo().getId().equals(owner.videoId()) || job.isRunning()
                || job.getFinishedAt() == null || !job.getFinishedAt().isBefore(jobThreshold)) return false;
        var directory = storage.frameDir(owner.videoId()).resolve("jobs/" + owner.jobId()).normalize();
        // Protect every persisted frame, including those referenced by an older retained report.
        for (var frame : frames.findByVideoIdOrderByTimeMsAsc(owner.videoId())) {
            if (storage.resolve(frame.getStorageKey()).startsWith(directory)) return false;
        }
        // Keep the Video lock through filesystem removal: a retry/result commit cannot race it.
        return storage.deleteExpiredJobFrames(owner.videoId(), owner.jobId(), markerThreshold);
    }
}
