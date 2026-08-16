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
        List<TimelineEventDto> events,

        /**
         * 분석 단계별 수행 결과. 명세 §19-5.
         * SUCCESS / FAILED / SKIPPED / NOT_ENABLED
         */
        List<CoverageDto> coverage,

        /**
         * 그중 사용자에게 알려야 하는 것만 추린 것. 명세 §5-1.
         *
         * 비어 있으면 필드 자체가 빠진다.
         * 값이 있으면 결과 위에 눈에 띄게 띄워야 한다 —
         * "확인할 지점 0곳" 이 "검수했더니 괜찮다" 로 읽히면 안 된다.
         */
        List<AnalysisWarningDto> warnings
) {}
