package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;

/** 재시도·취소 응답. 명세 §7 */
public record AnalysisRetryResponse(String videoId, String jobId, AnalysisStatus status) {

    public static AnalysisRetryResponse from(AnalysisJob job) {
        return new AnalysisRetryResponse(
                Ids.of(job.getVideo().getId()), job.getJobKey(), job.getStatus());
    }
}
