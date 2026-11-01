package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 자막 한 줄 (또는 STT 결과 한 조각). 타임코드가 있어야 논란 구간을 짚어줄 수 있다. */
@Getter
@Entity
@Table(name = "transcript_segment", indexes = @Index(name = "idx_segment_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    @Column(nullable = false)
    private long startMs;

    @Column(nullable = false)
    private long endMs;

    @Column(length = 2000, nullable = false)
    private String text;

    /** 이 행이 속한 분석 실행(analysis_job ID). redesign-v3 이전 행은 null */
    @Column(name = "run_id")
    private Long runId;

    public void assignRun(Long runId) {
        this.runId = runId;
    }

    public TranscriptSegment(Video video, long startMs, long endMs, String text) {
        this.video = video;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
