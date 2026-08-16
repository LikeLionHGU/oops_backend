package com.example.oops.dto;

import com.example.oops.domain.AnalysisCoverage;
import com.example.oops.domain.AnalyzerStatus;
import com.example.oops.domain.CoverageStep;

/**
 * 분석 단계별 수행 결과. 명세 §15-2 AnalysisCoverage.
 *
 * 후보 0건과 분석 실패를 같은 의미로 취급하지 않기 위한 필드다.
 */
public record CoverageDto(
        CoverageStep analyzer,
        AnalyzerStatus status,
        String message
) {
    public static CoverageDto from(AnalysisCoverage c) {
        return new CoverageDto(c.getStep(), c.getStatus(), c.getMessage());
    }
}
