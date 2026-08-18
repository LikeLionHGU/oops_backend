package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.ReviewAction;
import com.example.oops.domain.ReviewActionType;

/** 검수 결정 저장 응답. 명세 §6 */
public record ReviewActionResponse(
        String videoId,
        String eventId,
        ReviewActionType action,
        String note,
        String updatedAt
) {
    public static ReviewActionResponse from(ReviewAction a) {
        return new ReviewActionResponse(
                Ids.of(a.getVideo().getId()),
                Ids.of(a.getFinding().getId()),
                a.getAction(),
                a.getNote(),
                Ids.utc(a.getUpdatedAt()));
    }
}
