package com.example.videoguard.dto;

import com.example.videoguard.domain.AnalysisStatus;

import java.util.List;

/** API 명세 5-1 */
public record AnalysisReportResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status,
        RiskSummary summary,
        List<TimelineEventDto> events
) {}
