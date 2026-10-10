package com.example.oops.screentext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SrtParserTest {

    @Test
    @DisplayName("표준 SRT: 번호 / 시간 / 여러 줄 자막")
    void standard() {
        String srt = """
                1
                00:00:01,000 --> 00:00:03,500
                첫 번째 자막
                둘째 줄

                2
                00:01:02,250 --> 00:01:04,000
                두 번째 자막
                """;
        List<SrtParser.Cue> cues = SrtParser.parse(srt);
        assertThat(cues).hasSize(2);
        assertThat(cues.get(0)).isEqualTo(new SrtParser.Cue(1000, 3500, "첫 번째 자막 둘째 줄"));
        assertThat(cues.get(1).startMs()).isEqualTo(62_250);
        assertThat(cues.get(1).endMs()).isEqualTo(64_000);
    }

    @Test
    @DisplayName("WebVTT 형식, 점 구분자, 시 생략, 번호 없음도 읽는다")
    void vttLike() {
        String vtt = """
                WEBVTT

                00:05.000 --> 00:07.120
                시 없는 시간

                01:00:00.5 --> 01:00:01.000
                한 시간 지점
                """;
        List<SrtParser.Cue> cues = SrtParser.parse(vtt);
        assertThat(cues).extracting(SrtParser.Cue::text).containsExactly("시 없는 시간", "한 시간 지점");
        assertThat(cues.get(0).startMs()).isEqualTo(5000);
        assertThat(cues.get(1).startMs()).isEqualTo(3_600_500);
    }

    @Test
    @DisplayName("서식 태그, BOM, 윈도우 줄바꿈을 정리한다")
    void cleansFormatting() {
        String srt = "﻿1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>기울임</i> {\\an8}위쪽\r\n\r\n";
        List<SrtParser.Cue> cues = SrtParser.parse(srt);
        assertThat(cues).singleElement().extracting(SrtParser.Cue::text).isEqualTo("기울임 위쪽");
    }

    @Test
    @DisplayName("시간 정보가 없는 글은 자막으로 보지 않는다")
    void plainTextIsRejected() {
        assertThat(SrtParser.parse("그냥 대본 텍스트\n둘째 줄")).isEmpty();
        assertThat(SrtParser.parse("")).isEmpty();
        assertThat(SrtParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("한글 윈도우(CP949)로 저장한 파일도 읽는다")
    void decodesCp949() {
        String text = "1\n00:00:01,000 --> 00:00:02,000\n안녕하세요\n";
        byte[] cp949 = text.getBytes(Charset.forName("MS949"));
        assertThat(SrtParser.decode(cp949)).isEqualTo(text);
        assertThat(SrtParser.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
    }

    @Test
    @DisplayName("자막 사이 빈 줄이 없어도 다음 번호가 앞 자막에 붙지 않는다")
    void missingBlankLine() {
        String srt = "1\n00:00:01,000 --> 00:00:02,000\nhello\n2\n00:00:03,000 --> 00:00:04,000\nworld\n";
        assertThat(SrtParser.parse(srt)).extracting(SrtParser.Cue::text).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("UTF-16 으로 저장한 파일도 읽는다")
    void decodesUtf16() {
        String text = "1\n00:00:01,000 --> 00:00:02,000\n안녕하세요\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        assertThat(SrtParser.decode(withBom)).isEqualTo(text);
    }

    @Test
    @DisplayName("자막 한 줄이 너무 길면 저장 칸 길이(2000자)로 자른다")
    void longCueIsClipped() {
        String srt = "1\n00:00:01,000 --> 00:00:02,000\n" + "가".repeat(5000) + "\n";
        assertThat(SrtParser.parse(srt)).singleElement()
                .satisfies(c -> assertThat(c.text()).hasSize(SrtParser.MAX_CUE_CHARS));
    }
}
