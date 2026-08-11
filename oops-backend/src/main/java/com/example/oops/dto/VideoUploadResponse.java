package com.example.oops.dto;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.Video;

/** API 명세 2-1 */
public record VideoUploadResponse(
        Long videoId,
        String jobId,
        String filename,
        AnalysisStatus status,
        String streamUrl
) {
    public static VideoUploadResponse of(Video video, AnalysisJob job) {
        return new VideoUploadResponse(
                video.getId(),
                job.getJobKey(),
                video.getFilename(),
                job.getStatus(),
                video.isStreamable() ? "/api/v1/videos/" + video.getId() + "/stream" : null
        );
    }
}
