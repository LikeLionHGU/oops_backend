package com.example.oops.service;

import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** finding 목록을 종합 점수 + 한 줄 요약으로 집계한다. (내부 리포트용) */
@Component
public class ReportBuilder {

    public int calculateRiskScore(List<RiskFinding> findings) {
        if (findings.isEmpty()) return 0;

        // 가장 심각한 항목이 점수를 지배하고, 나머지는 완만하게 가산된다.
        double max = findings.stream().mapToDouble(RiskFinding::getScore).max().orElse(0);
        double weightSum = findings.stream().mapToInt(f -> f.getSeverity().weight()).sum();
        double extra = Math.min(20, weightSum * 1.5);

        return (int) Math.min(100, Math.round(max * 80 + extra));
    }

    public String buildSummary(List<RiskFinding> findings) {
        if (findings.isEmpty()) {
            return "탐지된 논란 요소가 없습니다.";
        }

        Map<RiskCategory, Long> byCategory = findings.stream()
                .collect(Collectors.groupingBy(RiskFinding::getCategory, Collectors.counting()));

        String breakdown = byCategory.entrySet().stream()
                .sorted(Map.Entry.<RiskCategory, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey().getLabel() + " " + e.getValue() + "건")
                .collect(Collectors.joining(", "));

        long highCount = findings.stream().filter(f -> f.getSeverity() == Severity.HIGH).count();
        long crossModalCount = findings.stream().filter(RiskFinding::isCrossModal).count();

        // fuse() 가 우선순위 내림차순으로 정렬해 두므로 첫 건이 가장 중요한 건이다
        RiskFinding top = findings.get(0);

        StringBuilder summary = new StringBuilder(
                "총 %d건의 논란 요소를 발견했습니다 (%s). 위험도 높음 %d건."
                        .formatted(findings.size(), breakdown, highCount));

        if (crossModalCount > 0) {
            summary.append(" 발언과 화면 양쪽에서 확인된 건 %d건.".formatted(crossModalCount));
        }

        summary.append(" 가장 먼저 확인할 부분: %s의 %s — \"%s\""
                .formatted(formatTime(top.getStartMs()), top.getCategory().getLabel(),
                        truncate(top.evidence())));

        return summary.length() > 2000 ? summary.substring(0, 2000) : summary.toString();
    }

    public static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%02d:%02d".formatted(minutes, seconds);
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }
}
