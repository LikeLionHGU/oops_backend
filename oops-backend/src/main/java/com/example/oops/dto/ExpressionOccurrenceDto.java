package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.ExpressionOccurrence;

/** UTF-16 raw-text offsets; times belong to the STT segment, not an aligned word. */
public record ExpressionOccurrenceDto(String id, String expressionId, String category, String status,
        String matchedText, String sourceType, String segmentId, long startMs, long endMs,
        String timePrecision, int startOffset, int endOffset, String offsetUnit,
        String dictionaryVersion, String commonUsageNote) {
    public static ExpressionOccurrenceDto from(ExpressionOccurrence h) {
        return new ExpressionOccurrenceDto(Ids.of(h.getId()), h.getExpressionId(), h.getCategory(),
                "EXPRESSION_DETECTED", h.getMatchedText(), "STT", h.getSegmentId(), h.getStartMs(), h.getEndMs(),
                "SEGMENT", h.getStartOffset(), h.getEndOffset(), "UTF16", h.getDictionaryVersion(), h.getCommonUsageNote());
    }
}
