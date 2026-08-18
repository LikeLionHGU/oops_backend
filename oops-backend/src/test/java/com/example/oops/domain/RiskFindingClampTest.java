package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검토 후보 구간이 영상 길이를 넘지 않는지. 명세 §9.
 *
 * OCR 은 프레임 간격만큼 endMs 를 잡는다.
 * 8초 영상을 1초 간격으로 뜨면 마지막 항목이 9000 까지 간다.
 * 프론트가 그 값으로 재생 위치를 잡으면 영상 끝으로 튄다.
 */
class RiskFindingClampTest {

    private RiskFinding finding(long startMs, long endMs) {
        return RiskFinding.builder()
                .eventType(TimelineEventType.CAPTION)
                .category(RiskCategory.SCREEN_TEXT)
                .source(EvidenceSource.VISION)
                .score(0.4)
                .startMs(startMs)
                .endMs(endMs)
                .captionText("화면 글자")
                .build();
    }

    @Test
    @DisplayName("영상 길이를 넘는 끝시간을 잘라낸다")
    void clampsEnd() {
        RiskFinding f = finding(7000, 9000);
        f.clampTo(8000);

        assertThat(f.getEndMs()).isEqualTo(8000);
        assertThat(f.getStartMs()).isEqualTo(7000);
    }

    @Test
    @DisplayName("영상 안에 있으면 건드리지 않는다")
    void leavesValidRange() {
        RiskFinding f = finding(3000, 5000);
        f.clampTo(8000);

        assertThat(f.getStartMs()).isEqualTo(3000);
        assertThat(f.getEndMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("시작도 영상 밖이면 마지막 1초를 준다")
    void handlesFullyOutOfRange() {
        // 잘라내고 나서 start == end 가 되면 클릭해도 아무 데도 못 간다
        RiskFinding f = finding(9000, 11000);
        f.clampTo(8000);

        assertThat(f.getEndMs()).isEqualTo(8000);
        assertThat(f.getStartMs()).isLessThan(f.getEndMs());
        assertThat(f.getStartMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("길이를 모르면 건드리지 않는다")
    void skipsWhenUnknown() {
        RiskFinding f = finding(7000, 9000);
        f.clampTo(0);

        assertThat(f.getEndMs()).isEqualTo(9000);
    }
}
