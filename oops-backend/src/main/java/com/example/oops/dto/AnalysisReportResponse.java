package com.example.oops.dto;

import com.example.oops.domain.AnalysisStatus;

import java.util.List;

/** API 명세 5-1 */
public record AnalysisReportResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status,
        RiskSummary summary,
        List<TimelineEventDto> events
) {}
