package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 분석 실행 1회의 기록.
 *
 * videoId 는 영상 리소스 식별자이고 jobId 는 실행 식별자다.
 * 재시도하면 videoId 는 그대로, jobId 는 새로 발급된다. (API 명세 2-1)
 */
@Getter
@Entity
@Table(name = "analysis_job",
       uniqueConstraints = @UniqueConstraint(name = "uk_job_key", columnNames = "job_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부에 노출되는 실행 식별자. 예: job_8fc391 */
    @Column(name = "job_key", nullable = false, length = 40)
    private String jobKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private AnalysisStatus status;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private AnalysisStage stage;

    /** 0 ~ 100 */
    @Column(nullable = false)
    private int progress;

    /** 사람이 읽는 현재 상태 설명 */
    @Column(length = 300)
    private String message;

    @Column(length = 50)
    private String errorCode;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public AnalysisJob(Video video) {
        this.jobKey = generateJobKey();
        this.video = video;
        this.status = AnalysisStatus.PENDING;
        this.stage = AnalysisStage.UPLOAD;
        this.progress = 0;
        this.message = AnalysisStage.UPLOAD.getDefaultMessage();
    }

    private static String generateJobKey() {
        return "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    public void start() {
        this.status = AnalysisStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
        updateProgress(AnalysisStage.STT, 5);
    }

    public void updateProgress(AnalysisStage stage, int progress) {
        updateProgress(stage, progress, stage.getDefaultMessage());
    }

    public void updateProgress(AnalysisStage stage, int progress, String message) {
        this.stage = stage;
        this.progress = Math.max(0, Math.min(progress, 99));
        this.message = message;
    }

    public void complete() {
        this.status = AnalysisStatus.COMPLETED;
        this.stage = AnalysisStage.COMPLETED;
        this.progress = 100;
        this.message = AnalysisStage.COMPLETED.getDefaultMessage();
        this.finishedAt = LocalDateTime.now();
    }

    public void fail(String errorCode, String message) {
        this.status = AnalysisStatus.FAILED;
        this.errorCode = errorCode;
        this.message = message != null && message.length() > 300
                ? message.substring(0, 300) : message;
        this.finishedAt = LocalDateTime.now();
    }

    public boolean isRunning() {
        return status.isRunning();
    }
}
