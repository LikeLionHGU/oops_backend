package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.Video;

/** 업로드 응답. 명세 §2 */
public record VideoUploadResponse(
        String videoId,
        String jobId,
        String filename,

        /** 업로드 직후 잰 영상 길이. 못 쟀으면 null */
        Long durationMs,

        AnalysisStatus status,

        /** 명세 §2 — 항상 존재한다. 원본이 없어도 같은 주소를 준다 */
        String streamUrl
) {
    public static VideoUploadResponse of(Video video, AnalysisJob job) {
        return new VideoUploadResponse(
                Ids.of(video.getId()),
                job.getJobKey(),
                video.getFilename(),
                video.durationMs(),
                job.getStatus(),
                "/api/v1/videos/%d/stream".formatted(video.getId())
        );
    }
}
