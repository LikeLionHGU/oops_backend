package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 탐지된 논란 1건. 발언이든 화면 자막이든 전부 이 테이블로 모인다.
 * 프론트에는 TimelineEvent 로 변환되어 나간다. (API 명세 6)
 */
@Getter
@Entity
@Table(name = "risk_finding", indexes = @Index(name = "idx_finding_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskFinding extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    /** 프론트 카드 분기 기준 */
    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private TimelineEventType eventType;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private RiskCategory category;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private Severity severity;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private EvidenceSource source;

    /** 0.0 ~ 1.0 확신도 */
    @Column(nullable = false)
    private double score;

    @Column(nullable = false)
    private long startMs;

    @Column(nullable = false)
    private long endMs;

    /** SPEECH 이벤트의 발언 내용 */
    @Column(length = 2000)
    private String text;

    /** CAPTION 이벤트: 실제 발언 */
    @Column(length = 2000)
    private String speechText;

    /** CAPTION 이벤트: 화면에 나온 자막 */
    @Column(length = 2000)
    private String captionText;

    /** 왜 문제인지 설명 */
    @Column(length = 1000)
    private String reason;

    /**
     * 이 지적이 향하는 대상.
     * 같은 대상에 대한 지적이면 유형이 달라도 한 건으로 묶는 데 쓴다.
     * "패스트푸드", "할머니" 처럼 짧은 말이 들어온다.
     */
    @Column(length = 200)
    private String target;

    /** 여러 번 등장했을 때 각각의 시각. "00:26, 00:35, 00:44" 형태. */
    @Column(length = 300)
    private String occurrenceTimes;

    /** 카드에 띄울 화면 캡처. 없으면 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_id")
    private VideoFrame frame;

    /** 노출 우선순위 (높을수록 먼저). FindingFusionService 가 계산한다. */
    @Column(nullable = false)
    private int priority;

    /** 발언과 화면 양쪽에서 확인된 건인지 */
    @Column(nullable = false)
    private boolean crossModal;

    /** 병합된 중복 후보 개수 (자기 자신 포함) */
    @Column(nullable = false)
    private int mergedCount;

    @Builder
    private RiskFinding(Video video, TimelineEventType eventType, RiskCategory category,
                        EvidenceSource source, double score, long startMs, long endMs,
                        String text, String speechText, String captionText,
                        String reason, String target, VideoFrame frame) {
        this.video = video;
        this.eventType = eventType == null ? TimelineEventType.SPEECH : eventType;
        this.category = category;
        this.source = source;
        this.score = score;
        this.severity = Severity.fromScore(score);
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.speechText = speechText;
        this.captionText = captionText;
        this.reason = reason;
        this.target = target;
        this.frame = frame;
        this.priority = 0;
        this.crossModal = false;
        this.mergedCount = 1;
    }

    /** 병합 단계에서만 호출한다. */
    public void applyFusion(int priority, boolean crossModal, int mergedCount) {
        this.priority = priority;
        this.crossModal = crossModal;
        this.mergedCount = mergedCount;
    }

    public void boostScore(double newScore) {
        this.score = Math.min(1.0, newScore);
        this.severity = Severity.fromScore(this.score);
    }

    public void appendReason(String extra) {
        String merged = (this.reason == null ? "" : this.reason + " ") + extra;
        this.reason = merged.length() > 1000 ? merged.substring(0, 1000) : merged;
    }

    /**
     * 같은 논란이 여러 번 등장할 때 구간을 넓힌다.
     * 영상 내내 떠 있는 고정 자막 같은 경우, 카드 하나로 "00:06 ~ 00:32" 처럼 보여주기 위해서다.
     */
    public void expandRange(long startMs, long endMs) {
        this.startMs = Math.min(this.startMs, startMs);
        this.endMs = Math.max(this.endMs, endMs);
    }

    /** 병합 단계에서 등장 시각 목록을 채운다. */
    public void recordOccurrences(String times) {
        this.occurrenceTimes = times;
    }

    public void attachFrame(VideoFrame frame) {
        if (this.frame == null) {
            this.frame = frame;
        }
    }

    /** 이 건의 핵심 텍스트. 중복 판단에 쓴다. */
    public String primaryText() {
        return eventType == TimelineEventType.CAPTION ? captionText : text;
    }

    /** 근거 원문. 병합 시 대표 선정과 로그에 쓴다. */
    public String evidence() {
        if (eventType == TimelineEventType.CAPTION) {
            return "발언: \"%s\" / 자막: \"%s\"".formatted(
                    speechText == null ? "-" : speechText,
                    captionText == null ? "-" : captionText);
        }
        return text;
    }
}
