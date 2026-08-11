package com.example.videoguard.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 화면에 박혀 있는 텍스트(편집 자막, 썸네일 문구 등) 1건. OCR 결과. */
@Getter
@Entity
@Table(name = "screen_text", indexes = @Index(name = "idx_screentext_video", columnList = "video_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScreenText {

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

    /** OCR 인식 신뢰도 0.0 ~ 1.0 */
    private Double confidence;

    /** 이 텍스트를 읽어낸 화면 캡처 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_id")
    private VideoFrame frame;

    public ScreenText(Video video, long startMs, long endMs, String text,
                      Double confidence, VideoFrame frame) {
        this.video = video;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.confidence = confidence;
        this.frame = frame;
    }
}
