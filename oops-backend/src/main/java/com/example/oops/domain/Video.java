package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "video")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private SourceType sourceType;

    /** 원본 파일명 (업로드일 때) */
    @Column(length = 300)
    private String filename;

    /** YOUTUBE 일 때 원본 링크 */
    @Column(length = 500)
    private String sourceUrl;

    /**
     * 저장소 키. 로컬에서는 storage.location 기준 상대 경로다.
     * 예: videos/123/original.mp4
     * 나중에 S3 로 바꿔도 이 값을 그대로 쓸 수 있게 절대경로를 쓰지 않는다.
     */
    @Column(length = 500)
    private String storageKey;

    @Column(length = 300)
    private String title;

    @Column(length = 200)
    private String channelName;

    private Integer durationSec;

    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private AnalysisStatus status;

    /**
     * 영상 유형. 업로드 시 지정하지 않으면 분석 중에 자동으로 판별한다.
     * 유형에 따라 실행되는 분석기가 달라진다.
     */
    @Enumerated(EnumType.STRING)
    // columnDefinition 을 명시하면 Hibernate 가 enum 체크 제약(CHECK ... IN (...))을 만들지 않는다.
    // 제약이 생기면 나중에 enum 값을 추가했을 때 기존 DB 에서 저장이 거부된다.
    @Column(columnDefinition = "varchar(40)")
    private ContentGenre genre;

    @Builder
    private Video(SourceType sourceType, String filename, String sourceUrl,
                  String storageKey, String title, String channelName,
                  Integer durationSec, ContentGenre genre) {
        this.sourceType = sourceType;
        this.filename = filename;
        this.sourceUrl = sourceUrl;
        this.storageKey = storageKey;
        this.title = title;
        this.channelName = channelName;
        this.durationSec = durationSec;
        this.genre = genre;
        this.status = AnalysisStatus.PENDING;
    }

    public void assignGenre(ContentGenre genre) {
        this.genre = genre;
    }

    public ContentGenre genreOrGeneral() {
        return genre == null ? ContentGenre.GENERAL : genre;
    }

    public void updateStatus(AnalysisStatus status) {
        this.status = status;
    }

    public void assignStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public void updateMetadata(String title, String channelName, Integer durationSec) {
        if (title != null) this.title = title;
        if (channelName != null) this.channelName = channelName;
        if (durationSec != null) this.durationSec = durationSec;
    }

    /**
     * 사용자의 검수 진행 상태. 명세 §4.
     * 분석 상태(status)와 별개다. 분석이 끝나도 사람이 안 봤을 수 있다.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(40)")
    private ReviewStatus reviewStatus;

    /** 검수를 마친 시각 */
    private java.time.LocalDateTime reviewedAt;

    public ReviewStatus reviewStatusOrDefault() {
        return reviewStatus == null ? ReviewStatus.NOT_STARTED : reviewStatus;
    }

    /** 첫 결정을 저장하면 검수를 시작한 것으로 본다. 명세 §6 */
    public void markReviewStarted() {
        if (reviewStatusOrDefault() == ReviewStatus.NOT_STARTED) {
            this.reviewStatus = ReviewStatus.IN_REVIEW;
        }
    }

    public void markReviewCompleted(java.time.LocalDateTime at) {
        this.reviewStatus = ReviewStatus.COMPLETED;
        this.reviewedAt = at;
    }

    /** 재분석하면 후보가 새로 발급되므로 검수도 처음부터다 */
    public void resetReview() {
        this.reviewStatus = ReviewStatus.NOT_STARTED;
        this.reviewedAt = null;
    }

    /** 명세는 밀리초를 쓴다. DB 는 초로 들고 있어서 여기서 바꾼다 */
    public Long durationMs() {
        return durationSec == null ? null : durationSec * 1000L;
    }

    /**
     * 원본 영상 파일을 지운 시각. 아직 갖고 있으면 null.
     *
     * 원본은 분석이 끝나면 더 필요 없다. 우리가 계속 들고 있을 이유가 없고,
     * 출연자 얼굴과 목소리가 그대로 담긴 파일이라 오래 둘수록 부담만 커진다.
     * 그래서 분석 완료 24시간 뒤에 파일만 지운다.
     *
     * **리포트·대본·검토 후보·참고 자료·검수 이력은 지우지 않는다.**
     * 그것까지 사라지면 사용자는 어제 받은 검수 결과를 다시 볼 수 없다.
     * 지우는 건 원본 미디어뿐이다.
     */
    private java.time.LocalDateTime sourcePurgedAt;

    public boolean isSourcePurged() {
        return sourcePurgedAt != null;
    }

    public void markSourcePurged(java.time.LocalDateTime at) {
        this.sourcePurgedAt = at;
    }

    /**
     * 영상을 재생할 수 있는지.
     *
     * 원본을 지운 뒤에는 false 다. 카드의 시각을 눌러도 영상이 안 뜨는데,
     * 이건 고장이 아니라 정책이다. 프론트는 streamUrl 이 null 이면
     * "보관 기간이 지나 원본은 삭제되었습니다" 를 보여주면 된다.
     */
    public boolean isStreamable() {
        return sourceType == SourceType.UPLOAD && storageKey != null && !isSourcePurged();
    }
}
