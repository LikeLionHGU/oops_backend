package com.example.oops.dto;

import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.Severity;

import java.util.List;

/** API 명세 5-1 의 summary. 심각도별 건수. */
public record RiskSummary(long high, long medium, long low) {

    public static RiskSummary of(List<RiskFinding> findings) {
        return new RiskSummary(
                count(findings, Severity.HIGH),
                count(findings, Severity.MEDIUM),
                count(findings, Severity.LOW)
        );
    }

    private static long count(List<RiskFinding> findings, Severity severity) {
        return findings.stream().filter(f -> f.getSeverity() == severity).count();
    }
}
