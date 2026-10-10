package com.example.oops.analyzer;

import com.example.oops.domain.ScreenText;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptionAlignmentTest {

    private final Video video = Video.builder().sourceType(SourceType.UPLOAD).build();

    private TranscriptSegment speech(long s, long e, String t) {
        return new TranscriptSegment(video, s, e, t);
    }

    private ScreenText caption(long s, long e, String t) {
        return new ScreenText(video, s, e, t, 1.0, null);
    }

    @Test
    @DisplayName("발언을 받아 적은 자막은 음성 인식이 몇 글자 틀려도 받아 적은 것으로 본다")
    void transcribedCaptionIsMirror() {
        List<TranscriptSegment> t = List.of(speech(1000, 4000, "오늘은 부산 어묵을 먹어 보겠습니다"));
        // 음성 인식이 '먹어'를 '머거'로 틀린 경우
        assertThat(CaptionAlignment.mirrorsSpeech(caption(1200, 3000, "오늘은 부산 어묵을 머거 보겠습니다"), t)).isTrue();
    }

    @Test
    @DisplayName("편집자가 덧붙인 예능 자막은 발언과 다르다")
    void editorialCaptionIsDistinct() {
        List<TranscriptSegment> t = List.of(speech(1000, 4000, "오늘은 부산 어묵을 먹어 보겠습니다"));
        ScreenText fun = caption(1500, 3000, "(먹방 장인 등판)");
        assertThat(CaptionAlignment.mirrorsSpeech(fun, t)).isFalse();
        assertThat(CaptionAlignment.distinctFromSpeech(List.of(fun), t)).containsExactly(fun);
    }

    @Test
    @DisplayName("같은 시간 발언이 없는 자막은 따로 본다")
    void captionWithoutSpeech() {
        List<TranscriptSegment> t = List.of(speech(1000, 2000, "안녕하세요"));
        ScreenText later = caption(30_000, 32_000, "안녕하세요");
        assertThat(CaptionAlignment.mirrorsSpeech(later, t)).isFalse();
    }

    @Test
    @DisplayName("짧은 자막은 발언 안에 그대로 있을 때만 받아 적은 것으로 본다")
    void shortCaption() {
        List<TranscriptSegment> t = List.of(speech(1000, 3000, "진짜 대박이다"));
        assertThat(CaptionAlignment.mirrorsSpeech(caption(1000, 2000, "대박"), t)).isTrue();
        assertThat(CaptionAlignment.mirrorsSpeech(caption(1000, 2000, "박대"), t)).isFalse();
    }

    @Test
    @DisplayName("긴 발언 옆에 뜬 비하 자막은 흔한 글자가 겹쳐도 받아 적은 것으로 보지 않는다")
    void editorialInsultOverLongSpeech() {
        List<TranscriptSegment> t = List.of(speech(0, 20_000,
                "이 사람이 진짜 오늘 여기까지 와 주셔서 너무 감사하고요 미리 준비한 이야기가 있는데 하나씩 천천히 들려 드릴게요 놈이 아니라 우리 이네"));
        ScreenText insult = caption(5000, 7000, "이 사람 진짜 미친놈이네");
        assertThat(CaptionAlignment.mirrorsSpeech(insult, t)).isFalse();
    }
}
