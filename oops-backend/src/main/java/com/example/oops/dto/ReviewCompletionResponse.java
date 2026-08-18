package com.example.oops.dto;

import com.example.oops.domain.ReviewStatus;

/** 검수 완료 응답. 명세 §6 */
public record ReviewCompletionResponse(
        String videoId,
        ReviewStatus reviewStatus,
        String reviewedAt,
        Summary summary
) {
    public record Summary(int total, int decided, int remaining,
                              int confirmed, int edited, int hold, int notUseful) {}
}
