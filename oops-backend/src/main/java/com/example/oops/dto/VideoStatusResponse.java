package com.example.oops.dto;

import com.example.oops.domain.AnalysisJob;
import com.example.oops.domain.AnalysisStage;
import com.example.oops.domain.AnalysisStatus;

/** API 명세 3-1 */
public record VideoStatusResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status,
        int progress,
        AnalysisStage stage,
        String message,

        /**
         * 실패했을 때만 채워진다. 명세 §3-1.
         *
         * message 는 사람에게 보여줄 문구이고 이건 프론트가 분기할 고정 코드다.
         * STOMP ProgressMessage 와 같은 값을 준다 —
         * WebSocket 이 끊겨 폴링으로 넘어가도 화면 분기가 달라지면 안 된다.
         */
        String errorCode
) {
    public static VideoStatusResponse from(AnalysisJob job) {
        return new VideoStatusResponse(
                job.getVideo().getId(),
                job.getJobKey(),
                job.getStatus(),
                job.getProgress(),
                job.getStage(),
                job.getMessage(),
                job.getErrorCode()
        );
    }
}
