package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분석기 내부 기록(후보·점검·제외·중단·검색어·판정). (redesign-v3 8단계)
 * 사용자 화면에는 보여주지 않는다. 평가·디버깅용 텍스트라 원본 영상이 지워진 뒤에도 남는다.
 */
@Getter
@Entity
@Table(name = "analysis_trace", indexes = @Index(name = "idx_trace_run", columnList = "run_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisTrace extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(nullable = false, length = 60)
    private String analyzer;

    @Lob
    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    public AnalysisTrace(Video video, Long runId, String analyzer, String content) {
        this.video = video;
        this.runId = runId;
        this.analyzer = analyzer;
        this.content = content;
    }
}
