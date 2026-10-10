package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분석 단계 하나가 실제로 수행됐는지. 명세 §19-5.
 *
 * 이걸 만든 이유:
 *   분석기 하나가 실패해도 나머지 결과로 COMPLETED 가 됐다.
 *   사용자 화면에는 "확인할 지점 없음" 만 떴다.
 *   실제로는 이름·수치 확인이 요청 한도 때문에 아예 못 돈 경우였다.
 *
 *   "봤는데 없다" 와 "보지도 못했다" 는 전혀 다른 이야기인데
 *   화면에서는 구분이 안 됐다. 검수 도구에서 이건 치명적이다.
 *   괜찮다고 믿고 올렸는데 검수가 안 된 상태일 수 있다.
 */
@Getter
@Entity
@Table(name = "analysis_coverage",
        indexes = @Index(name = "idx_coverage_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisCoverage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private CoverageStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private AnalyzerStatus status;

    /** 왜 실패했는지 또는 왜 건너뛰었는지. 성공이면 비어 있다 */
    @Column(length = 300)
    private String message;

    private AnalysisCoverage(Video video, CoverageStep step,
                             AnalyzerStatus status, String message) {
        this.video = video;
        this.step = step;
        this.status = status;
        this.message = boundedMessage(message);
    }

    private static String boundedMessage(String message) {
        if (message == null || message.length() <= 300) return message;
        String suffix = "… (일부 생략)";
        int end = 300 - suffix.length();
        if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
        return message.substring(0, end) + suffix;
    }

    /** Preserve each analyser's warning summary instead of chopping the last warning off at 300 chars. */
    public static String combineMessages(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) return existing;
        if (existing == null || existing.isBlank()) return boundedMessage(incoming.replaceAll("\\s+", " ").strip());
        var messages=new java.util.LinkedHashSet<String>(java.util.Arrays.asList(existing.split("\\n")));
        messages.add(incoming.replaceAll("\\s+", " ").strip());
        int budget=Math.max(1, (300 - messages.size() + 1) / messages.size());
        return messages.stream().map(s -> {
            if (s.length() <= budget) return s;
            int end=budget-1;
            if (end > 0 && Character.isHighSurrogate(s.charAt(end-1))) end--;
            return s.substring(0, end) + "…";
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    public static AnalysisCoverage of(Video video, CoverageStep step,
                                      AnalyzerStatus status, String message) {
        return new AnalysisCoverage(video, step, status, message);
    }

    /** 사용자에게 알려야 하는 상태인지. 일부 확인과 실패도 알려야 한다. */
    public boolean needsWarning() {
        return status == AnalyzerStatus.FAILED || status == AnalyzerStatus.SKIPPED
                || status == AnalyzerStatus.PARTIAL;
    }

    /** 프론트가 분기할 고정 코드. 예: OCR_UNAVAILABLE, FACT_ENTITY_UNAVAILABLE */
    public String warningCode() {
        if (status == AnalyzerStatus.PARTIAL) {
            return step.name() + "_PARTIAL";
        }
        return step == CoverageStep.OCR ? "OCR_UNAVAILABLE" : step.name() + "_UNAVAILABLE";
    }
}
