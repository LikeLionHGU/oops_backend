package com.example.oops.domain;

import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 영상만 지우는 정책.
 *
 * 이 테스트가 있는 이유는, 예전 코드가 정리 스케줄러에서
 * 영상에 딸린 **모든 것**을 지웠기 때문이다.
 * 리포트도 대본도 검수 이력도 같이 사라졌다.
 *
 * 사용자가 원하는 건 그게 아니다.
 *   원본 영상 → 오래 들고 있고 싶지 않다
 *   검수 결과 → 나중에 다시 보고 싶다
 *
 * 삭제 대상을 잘못 잡으면 되돌릴 방법이 없어서 테스트로 못 박아 둔다.
 */
class VideoSourcePurgeTest {

    private Video uploaded() {
        return Video.builder()
                .sourceType(SourceType.UPLOAD)
                .filename("talk.mp4")
                .storageKey("videos/1/original.mp4")
                .durationSec(3600)
                .build();
    }

    @Test
    @DisplayName("원본이 있으면 재생할 수 있다")
    void streamableWhileSourceExists() {
        assertThat(uploaded().isStreamable()).isTrue();
    }

    @Test
    @DisplayName("원본을 지우면 재생할 수 없다")
    void notStreamableAfterPurge() {
        Video video = uploaded();
        video.markSourcePurged(LocalDateTime.now());

        assertThat(video.isSourcePurged()).isTrue();
        assertThat(video.isStreamable()).isFalse();
    }

    @Test
    @DisplayName("원본을 지워도 검수 이력은 남는다")
    void keepsReviewHistoryAfterPurge() {
        // 이게 이 변경의 전부다.
        // 파일은 사라져도 사용자가 어제 내린 결정은 그대로 있어야 한다.
        Video video = uploaded();
        LocalDateTime reviewedAt = LocalDateTime.now().minusDays(2);
        video.markReviewCompleted(reviewedAt);

        video.markSourcePurged(LocalDateTime.now());

        assertThat(video.reviewStatusOrDefault()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(video.getReviewedAt()).isEqualTo(reviewedAt);
        assertThat(video.getDurationSec()).isEqualTo(3600);
    }

    @Test
    @DisplayName("유튜브로 등록한 영상은 원래 재생 대상이 아니다")
    void youtubeIsNotStreamable() {
        Video video = Video.builder()
                .sourceType(SourceType.YOUTUBE)
                .sourceUrl("https://youtu.be/abc")
                .build();

        assertThat(video.isStreamable()).isFalse();
        assertThat(video.isSourcePurged()).isFalse();   // 지운 게 아니라 원래 없다
    }

    @Test
    @DisplayName("보관 설정 기본값 — 원본 24시간, 전체 삭제는 꺼짐")
    void retentionDefaults() {
        OopsProperties.Storage empty = new OopsProperties.Storage("./uploads", null, null);

        assertThat(empty.sourceRetentionHoursOrDefault()).isEqualTo(24);
        // 전체 삭제가 기본으로 켜져 있으면 안 된다. 리포트까지 사라진다.
        assertThat(empty.retentionDaysOrDefault()).isZero();
    }

    @Test
    @DisplayName("0 을 주면 원본을 지우지 않는다")
    void zeroDisablesSourcePurge() {
        OopsProperties.Storage off = new OopsProperties.Storage("./uploads", 0, 0);

        assertThat(off.sourceRetentionHoursOrDefault()).isZero();
    }
}
