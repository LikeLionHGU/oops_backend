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
    @Column(nullable = false, length = 20)
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
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    /**
     * 영상 유형. 업로드 시 지정하지 않으면 분석 중에 자동으로 판별한다.
     * 유형에 따라 실행되는 분석기가 달라진다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
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

    public boolean isStreamable() {
        return sourceType == SourceType.UPLOAD && storageKey != null;
    }
}
