package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분석 실행 한 번의 기록. (redesign-v3 8단계)
 *
 * 재분석해도 이전 실행의 결과 카드·전사·수행 기록·검수 기록을 지우지 않는다.
 * 각 행은 runId(= 분석 작업 ID)로 실행에 묶이고, {@link Video#getCurrentRunId()} 가 화면에 보여줄 실행을 가리킨다.
 */
@Getter
@Entity
@Table(name = "analysis_run", indexes = @Index(name = "idx_run_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRun extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    /** 분석 작업(analysis_job) ID. 결과 행의 run_id 와 같다. */
    @Column(nullable = false, unique = true)
    private Long jobId;

    /** 판단 기준 버전 (예: oops-all@0.4-draft). 맥락 확인을 안 돌렸으면 null */
    @Column(length = 120)
    private String criteriaVersion;

    @Column(length = 80)
    private String model;

    private int riskScore;

    private int findingCount;

    @Column(length = 2000)
    private String summary;

    /** 저장 전사를 재사용한 재분석인지 */
    private boolean transcriptReused;

    public AnalysisRun(Video video, Long jobId, String criteriaVersion, String model, int riskScore,
                       int findingCount, String summary, boolean transcriptReused) {
        this.video = video;
        this.jobId = jobId;
        this.criteriaVersion = criteriaVersion;
        this.model = model;
        this.riskScore = riskScore;
        this.findingCount = findingCount;
        this.summary = summary;
        this.transcriptReused = transcriptReused;
    }
}
