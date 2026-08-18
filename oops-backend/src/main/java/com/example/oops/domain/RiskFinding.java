package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 탐지된 논란 1건. 발언이든 화면 자막이든 전부 이 테이블로 모인다.
 * 프론트에는 TimelineEvent 로 변환되어 나간다. (API 명세 6)
 */
@Getter
@Entity
@Table(name = "risk_finding", indexes = @Index(name = "idx_finding_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskFinding extends BaseTimeEntity {

    /** 카드 하나에 붙일 참고 자료 상한. 많이 붙이면 아무도 안 본다. */
    private static final int MAX_REFERENCES = 4;

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

    /**
     * AI 가 실제로 본 참고 자료. 사용자가 직접 확인할 수 있게 남긴다.
     *
     * 설명만 주고 근거를 버리면 결국 AI 말을 믿으라는 것과 같다.
     * 특히 이 도구는 오탐이 나므로, 무관한 기사였다는 걸
     * 사용자가 스스로 판단할 수 있어야 한다.
     */
    @OneToMany(mappedBy = "finding", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ReviewReference> references = new ArrayList<>();

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

    /**
     * 참고 자료를 붙인다.
     *
     * 카드 하나에 기사를 다 쏟아놓으면 오히려 안 보게 된다.
     * 확인할 만큼만 남기고, 같은 기사는 한 번만 넣는다.
     */
    public void addReference(ReviewReference reference) {
        if (reference == null || references.size() >= MAX_REFERENCES) {
            return;
        }
        String key = reference.dedupeKey();
        if (key.isBlank()) {
            return;
        }
        boolean duplicate = references.stream()
                .anyMatch(r -> key.equals(r.dedupeKey()));
        if (duplicate) {
            return;
        }
        reference.assignTo(this, references.size());
        references.add(reference);
    }

    /**
     * 병합으로 버려지는 후보가 들고 있던 참고 자료를 넘겨받는다.
     *
     * 같은 지적을 여러 분석기가 보고하면 대표 1건만 남기는데,
     * 그때 버려지는 쪽의 근거까지 같이 사라지면 안 된다.
     */
    public void adoptReferences(List<ReviewReference> incoming) {
        if (incoming == null) return;
        for (ReviewReference reference : List.copyOf(incoming)) {
            addReference(reference);
        }
    }

    /**
     * 구간이 영상 길이를 넘지 않게 맞춘다. 명세 §5·§9.
     *
     * OCR 은 프레임 간격만큼 endMs 를 잡기 때문에 마지막 자막이 영상 밖으로 나간다.
     * 8초 영상인데 endMs 가 9000 으로 나오는 식이다.
     * 프론트가 그 값으로 재생 위치를 잡으면 영상 끝으로 튄다.
     */
    public void clampTo(long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        this.endMs = Math.min(this.endMs, durationMs);
        this.startMs = Math.max(0, Math.min(this.startMs, this.endMs));

        // 잘라내고 나서 길이가 0이 되면 최소 구간을 준다.
        // start == end 면 프론트에서 클릭해도 아무 데도 못 간다.
        if (this.endMs <= this.startMs) {
            this.startMs = Math.max(0, durationMs - 1000);
            this.endMs = durationMs;
        }
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
