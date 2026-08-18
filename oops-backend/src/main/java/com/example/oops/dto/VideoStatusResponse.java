package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStage;
import com.example.oops.domain.AnalysisStatus;

/** 분석 상태. 명세 §3. STOMP 진행률 메시지도 같은 구조를 쓴다. */
public record VideoStatusResponse(
        String videoId,
        String jobId,
        String filename,
        Long durationMs,
        AnalysisStatus status,
        AnalysisStage stage,

        /** 0~100. 같은 jobId 안에서는 감소하지 않는다 */
        int progress,

        String message,

        String startedAt,
        String updatedAt,
        String completedAt,

        /** 실패했을 때만. 성공 중에는 null */
        Failure failure
) {
    /** 실패 사유. 프론트는 code 로 분기하고 문구는 직접 정한다 */
    public record Failure(String code, String message) {}

    public static VideoStatusResponse from(AnalysisJob job) {
        var video = job.getVideo();
        boolean failed = job.getStatus() == AnalysisStatus.FAILED;

        return new VideoStatusResponse(
                Ids.of(video.getId()),
                job.getJobKey(),
                video.getFilename(),
                video.durationMs(),
                job.getStatus(),
                job.getStage(),
                job.getProgress(),
                job.getMessage(),
                Ids.utc(job.getStartedAt()),
                Ids.utc(job.getUpdatedAt()),
                Ids.utc(job.getFinishedAt()),
                failed ? new Failure(
                        job.getErrorCode() == null ? "ANALYSIS_FAILED" : job.getErrorCode(),
                        job.getMessage()) : null
        );
    }
}
