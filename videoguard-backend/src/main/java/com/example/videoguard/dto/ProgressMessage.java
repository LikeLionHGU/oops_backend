package com.example.videoguard.dto;

import com.example.videoguard.domain.AnalysisJob;
import com.example.videoguard.domain.AnalysisStage;
import com.example.videoguard.domain.AnalysisStatus;

/**
 * API 명세 4-2. WebSocket 으로 나가는 메시지.
 * 상태 조회 응답과 필드를 일부러 맞춰서 프론트가 같은 핸들러로 처리할 수 있게 했다.
 */
public record ProgressMessage(
        Long videoId,
        String jobId,
        AnalysisStatus status,
        int progress,
        AnalysisStage stage,
        String message,
        String errorCode
) {
    public static ProgressMessage from(AnalysisJob job) {
        return new ProgressMessage(
                job.getVideo().getId(),
                job.getJobKey(),
                job.getStatus(),
                job.getStatus() == AnalysisStatus.COMPLETED ? 100 : job.getProgress(),
                job.getStage(),
                job.getMessage(),
                job.getErrorCode()
        );
    }
}
