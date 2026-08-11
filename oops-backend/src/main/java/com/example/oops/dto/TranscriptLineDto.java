package com.example.oops.dto;

import com.example.oops.domain.ScreenText;
import com.example.oops.domain.TranscriptSegment;

/** 대본/화면자막 원문 조회용. 디버깅과 대본 패널에 쓴다. */
public record TranscriptLineDto(
        long startMs,
        long endMs,
        String text,
        Double confidence,
        String frameUrl
) {
    public static TranscriptLineDto from(TranscriptSegment s) {
        return new TranscriptLineDto(s.getStartMs(), s.getEndMs(), s.getText(), null, null);
    }

    public static TranscriptLineDto from(ScreenText s) {
        String frameUrl = s.getFrame() == null ? null
                : "/api/v1/videos/%d/frames/%d".formatted(s.getVideo().getId(), s.getFrame().getId());
        return new TranscriptLineDto(s.getStartMs(), s.getEndMs(), s.getText(),
                s.getConfidence(), frameUrl);
    }
}
