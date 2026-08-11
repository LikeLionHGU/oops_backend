package com.example.oops.dto;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;

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
