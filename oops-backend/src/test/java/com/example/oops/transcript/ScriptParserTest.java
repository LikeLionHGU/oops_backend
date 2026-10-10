package com.example.oops.transcript;

import com.example.oops.transcript.TranscriptProvider.TranscriptLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptParserTest {

    @Test
    @DisplayName("유튜브 스크립트 창 복사본: 시각 한 줄, 글 한 줄")
    void timestampOnOwnLine() {
        String script = """
                스크립트
                0:00
                아 아니 여기 중국 아니에요?
                0:03
                아니 그니까 이건 옆으로 읽는 거 아이가?
                1:02:05
                한 시간 지점
                """;
        ScriptParser.Result r = ScriptParser.parse(script);
        assertThat(r.estimated()).isFalse();
        assertThat(r.lines()).extracting(TranscriptLine::text).containsExactly(
                "아 아니 여기 중국 아니에요?", "아니 그니까 이건 옆으로 읽는 거 아이가?", "한 시간 지점");
        assertThat(r.lines().get(0).startMs()).isZero();
        assertThat(r.lines().get(0).endMs()).isEqualTo(3000);   // 다음 시각까지
        assertThat(r.lines().get(2).startMs()).isEqualTo(3_725_000);
        assertThat(r.durationSec()).isGreaterThanOrEqualTo(3726);
    }

    @Test
    @DisplayName("읽기용 시각 문구(5초, 1분 5초)와 소리 표시([음악])는 지운다")
    void removesSpokenTimeAndSoundTags() {
        String script = """
                0:05
                5초
                [음악] 안녕하세요 여러분
                1:05
                1분 5초
                오늘은 (웃음) 부산에 왔어요
                """;
        assertThat(ScriptParser.parse(script).lines()).extracting(TranscriptLine::text)
                .containsExactly("안녕하세요 여러분", "오늘은 부산에 왔어요");
    }

    @Test
    @DisplayName("같은 줄에 붙은 시각, 대괄호 시각, 줄바꿈이 사라진 한 줄도 읽는다")
    void inlineAndSingleLine() {
        assertThat(ScriptParser.parse("[0:00] 첫 줄\n[0:04] 둘째 줄").lines())
                .extracting(TranscriptLine::text).containsExactly("첫 줄", "둘째 줄");

        List<TranscriptLine> oneLine = ScriptParser.parse("0:00 아 아니 여기 중국 0:03 아니 그니까 0:07 롯데리아 없나?").lines();
        assertThat(oneLine).extracting(TranscriptLine::text).containsExactly("아 아니 여기 중국", "아니 그니까", "롯데리아 없나?");
        assertThat(oneLine).extracting(TranscriptLine::startMs).containsExactly(0L, 3000L, 7000L);
    }

    @Test
    @DisplayName("문장 속 '3:30에' 처럼 글자에 붙은 시각은 나누는 기준이 아니다")
    void ignoresTimesInsideSentences() {
        List<TranscriptLine> lines = ScriptParser.parse("0:00 우리 3:30에 만나요\n0:04 좋아요").lines();
        assertThat(lines).extracting(TranscriptLine::text).containsExactly("우리 3:30에 만나요", "좋아요");
    }

    @Test
    @DisplayName("시각이 없는 글은 줄·문장으로 나누고 글자 수로 시각을 추정한다")
    void estimatesWithoutTimestamps() {
        ScriptParser.Result r = ScriptParser.parse("""
                안녕하세요 여러분
                오늘은 부산에 왔습니다. 날씨가 정말 좋네요! 바로 시장으로 가 볼게요.
                """);
        assertThat(r.estimated()).isTrue();
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(1).startMs()).isEqualTo(r.lines().get(0).endMs());

        String longLine = "가".repeat(30) + " " + "나".repeat(30) + " " + "다".repeat(30);
        assertThat(ScriptParser.parse(longLine).lines()).hasSizeGreaterThan(1)
                .allSatisfy(l -> assertThat(l.text().length()).isLessThanOrEqualTo(ScriptParser.SPLIT_OVER_CHARS));
    }

    @Test
    @DisplayName("빈 글, 시각만 있는 글은 읽을 문장이 없다")
    void emptyInputs() {
        assertThat(ScriptParser.parse(null).isEmpty()).isTrue();
        assertThat(ScriptParser.parse("  \n ").isEmpty()).isTrue();
        assertThat(ScriptParser.parse("0:00\n0:03\n[음악]").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("시각이 다음 시각보다 한참 앞이면 15초까지만 잡는다")
    void capsLongGaps() {
        List<TranscriptLine> lines = ScriptParser.parse("0:00 짧은 말\n2:00 다음 말").lines();
        assertThat(lines.get(0).endMs()).isEqualTo(ScriptParser.MAX_LINE_MS);
    }
}
