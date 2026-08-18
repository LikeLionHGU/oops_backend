package com.example.oops.dto;

import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.ReviewStatus;

import java.util.List;

/** 검수 리포트. 명세 §5 */
public record AnalysisReportResponse(
        String videoId,
        String jobId,
        String filename,

        /** 리포트 생성 시각. ISO-8601 UTC */
        String generatedAt,

        Long durationMs,
        String streamUrl,

        /** 분석 상태와 별개인 사용자의 검수 진행 상태 */
        ReviewStatus reviewStatus,

        AnalysisStatus status,
        RiskSummary summary,
        ReviewSummaryDto reviewSummary,
        CoverageDto coverage,

        /** 수행하지 못한 단계. 없으면 빈 배열 */
        List<AnalysisWarningDto> warnings,

        List<TimelineEventDto> events,

        // ---- 아래는 내부 확장. 프론트 계약은 아니다 ----
        ContentGenre genre
) {}
