package com.example.videoguard.dto;

import com.example.videoguard.domain.AnalysisJob;
import com.example.videoguard.domain.AnalysisStatus;

/** API 명세 9-1 */
public record AnalysisRetryResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status
) {
    public static AnalysisRetryResponse from(AnalysisJob job) {
        return new AnalysisRetryResponse(
                job.getVideo().getId(),
                job.getJobKey(),
                job.getStatus()
        );
    }
}
