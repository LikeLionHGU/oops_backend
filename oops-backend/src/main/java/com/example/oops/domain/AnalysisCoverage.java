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
        this.message = message;
    }

    public static AnalysisCoverage of(Video video, CoverageStep step,
                                      AnalyzerStatus status, String message) {
        return new AnalysisCoverage(video, step, status, message);
    }

    /** 사용자에게 알려야 하는 상태인지. 성공과 미사용은 알릴 필요가 없다 */
    public boolean needsWarning() {
        return status == AnalyzerStatus.FAILED || status == AnalyzerStatus.SKIPPED;
    }

    /** 프론트가 분기할 고정 코드. 예: OCR_UNAVAILABLE, FACT_ENTITY_UNAVAILABLE */
    public String warningCode() {
        return step == CoverageStep.OCR ? "OCR_UNAVAILABLE" : step.name() + "_UNAVAILABLE";
    }
}
