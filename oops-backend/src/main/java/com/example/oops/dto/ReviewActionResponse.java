package com.example.oops.dto;

import com.example.oops.domain.ReviewAction;
import com.example.oops.domain.ReviewActionType;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 명세 §9-2 응답 */
public record ReviewActionResponse(
        Long videoId,
        Long eventId,
        ReviewActionType action,
        String note,

        /** ISO-8601 UTC */
        String updatedAt
) {
    public static ReviewActionResponse from(ReviewAction a) {
        return new ReviewActionResponse(
                a.getVideo().getId(),
                a.getFinding().getId(),
                a.getAction(),
                a.getNote(),
                a.getUpdatedAt() == null ? null
                        : a.getUpdatedAt().atZone(java.time.ZoneId.systemDefault())
                                .withZoneSameInstant(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_INSTANT)
        );
    }
}
