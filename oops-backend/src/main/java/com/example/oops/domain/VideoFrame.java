package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OCR 에 사용된 화면 캡처 1장.
 * 프론트가 리스크 카드에 썸네일을 띄울 수 있게 파일을 보관한다. (API 명세 8)
 */
@Getter
@Entity
@Table(name = "video_frame", indexes = @Index(name = "idx_frame_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id")
    private Video video;

    /** 이 프레임이 영상에서 몇 ms 지점인지 */
    @Column(nullable = false)
    private long timeMs;

    /** storage.location 기준 상대 경로. 예: frames/123/0042000.jpg */
    @Column(nullable = false, length = 500)
    private String storageKey;

    @Column(length = 30)
    private String contentType;

    public VideoFrame(Video video, long timeMs, String storageKey, String contentType) {
        this.video = video;
        this.timeMs = timeMs;
        this.storageKey = storageKey;
        this.contentType = contentType == null ? "image/jpeg" : contentType;
    }
}
