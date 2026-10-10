package com.example.oops.domain;

import java.util.List;

/** Only populated after evidence validation, never from discovery hypotheses. */
public record FindingSupport(String anchorId, String targetType, List<Quote> quotes) {
    public FindingSupport { quotes = List.copyOf(quotes); }
    public record Quote(String segmentId, TimelineEventType type, long startMs, long endMs,
                        String quote, String role) {}
}
