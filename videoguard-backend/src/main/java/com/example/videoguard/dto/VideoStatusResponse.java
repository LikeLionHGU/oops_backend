package com.example.videoguard.dto;

import com.example.videoguard.domain.AnalysisJob;
import com.example.videoguard.domain.AnalysisStage;
import com.example.videoguard.domain.AnalysisStatus;

/** API 명세 3-1 */
public record VideoStatusResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status,
        int progress,
        AnalysisStage stage,
        String message
) {
    public static VideoStatusResponse from(AnalysisJob job) {
        return new VideoStatusResponse(
                job.getVideo().getId(),
                job.getJobKey(),
                job.getStatus(),
                job.getProgress(),
                job.getStage(),
                job.getMessage()
        );
    }
}
