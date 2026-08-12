package com.example.oops.dto;

import com.example.oops.domain.AdSuitability;
import com.example.oops.domain.AnalysisStatus;
import com.example.oops.domain.ContentGenre;

import java.util.List;

/** API 명세 5-1 */
public record AnalysisReportResponse(
        Long videoId,
        String jobId,
        AnalysisStatus status,

        /** 영상 유형. 자동 판별되거나 업로드 시 지정한 값 */
        ContentGenre genre,

        /**
         * 유튜브 광고 적합성 예측. MONETIZED / LIMITED / DEMONETIZED
         * 가장 심한 구간을 기준으로 한다.
         * (명세에 없는 추가 필드다)
         */
        AdSuitability adSuitability,
        String adSuitabilityNote,

        RiskSummary summary,
        List<TimelineEventDto> events
) {}
