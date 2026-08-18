package com.example.oops.dto;

import com.example.oops.domain.CandidateType;
import com.example.oops.domain.RiskFinding;

import java.util.List;

/**
 * 검토 후보 집계. 명세 v2.1 §10.
 *
 * 예전에는 심각도(high/medium/low)로 나눴는데 유형별로 바꿨습니다.
 * "위험도 높음 3건" 은 판정처럼 읽히고, 우리가 하는 일은 판정이 아닙니다.
 * "다시 읽어볼 표현 5건, 사실 확인 4건" 이 화면에서 할 일과 바로 이어집니다.
 */
public record RiskSummary(int total, int speechReview, int factCheck) {

    public static RiskSummary of(List<RiskFinding> findings) {
        int speech = 0;
        int fact = 0;
        for (RiskFinding f : findings) {
            if (CandidateType.from(f.getCategory()) == CandidateType.FACT_CHECK) {
                fact++;
            } else {
                speech++;
            }
        }
        return new RiskSummary(findings.size(), speech, fact);
    }
}
