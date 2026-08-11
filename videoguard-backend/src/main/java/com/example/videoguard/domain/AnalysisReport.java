package com.example.videoguard.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 영상 전체에 대한 요약 리포트. finding 들을 집계해서 만든다. */
@Getter
@Entity
@Table(name = "analysis_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", unique = true)
    private Video video;

    /** 0 ~ 100 종합 위험도 */
    @Column(nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity overallSeverity;

    @Column(nullable = false)
    private int findingCount;

    @Column(length = 2000)
    private String summary;

    public AnalysisReport(Video video, int riskScore, int findingCount, String summary) {
        this.video = video;
        this.riskScore = riskScore;
        this.overallSeverity = Severity.fromScore(riskScore / 100.0);
        this.findingCount = findingCount;
        this.summary = summary;
    }

    public void update(int riskScore, int findingCount, String summary) {
        this.riskScore = riskScore;
        this.overallSeverity = Severity.fromScore(riskScore / 100.0);
        this.findingCount = findingCount;
        this.summary = summary;
    }
}
