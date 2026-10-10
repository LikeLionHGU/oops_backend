package com.example.oops.screentext;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT 자막 파일 읽기. (2026-10 고도화)
 *
 * 편집 툴(프리미어 프로, 다빈치 리졸브, 파이널 컷, Vrew, 캡컷)이 내보내는 표준 형식을 받는다.
 *
 *   1
 *   00:00:01,000 --> 00:00:03,500
 *   첫 번째 자막
 *
 * 흔한 변형도 받는다.
 *   · 밀리초 구분자가 점인 경우 (00:00:01.000) — WebVTT 와 일부 툴
 *   · 시(hour)가 없는 경우 (00:01.000)
 *   · 번호 줄이 없는 경우, WEBVTT 머리말
 *   · 서식 태그 (<i>, <font>, {\an8})
 *   · UTF-8 BOM, UTF-16(BOM 있음), 윈도우 줄바꿈, 한글 윈도우 인코딩(CP949) 파일
 *   · 자막 사이 빈 줄이 빠진 파일 (다음 번호 줄이 앞 자막 글에 붙지 않게 한다)
 *
 * 시간 정보가 없는 텍스트는 받지 않는다. 발언과 시간을 맞출 수 없기 때문이다.
 */
public final class SrtParser {

    /** 한 자막의 시작·끝(ms)과 글 */
    public record Cue(long startMs, long endMs, String text) {}

    private static final Pattern TIMING = Pattern.compile(
            "((?:\\d{1,2}:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})");
    private static final Pattern TAGS = Pattern.compile("<[^>]{1,40}>|\\{\\\\[^}]{1,40}}");

    /** 자막 한 줄 최대 길이. 저장 칸(ScreenText.text)이 2000자라 넘으면 저장이 실패한다. */
    static final int MAX_CUE_CHARS = 2000;

    private static final Pattern CUE_NUMBER = Pattern.compile("\\d{1,6}");

    private SrtParser() {
    }

    /** 파일 바이트를 글로. UTF-16 BOM 이 있으면 UTF-16, 아니면 UTF-8, 그것도 아니면 CP949(한글 윈도우). */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            if (b0 == 0xFE && b1 == 0xFF) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("MS949"));
        }
    }

    public static List<Cue> parse(String srt) {
        List<Cue> cues = new ArrayList<>();
        if (srt == null || srt.isBlank()) return cues;

        String normalized = srt.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        long start = -1;
        long end = -1;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher m = TIMING.matcher(line);
            if (m.find()) {
                flush(cues, start, end, text);
                start = toMs(m.group(1));
                end = toMs(m.group(2));
                text.setLength(0);
                continue;
            }
            if (line.isEmpty()) {
                flush(cues, start, end, text);
                start = -1;
                end = -1;
                text.setLength(0);
                continue;
            }
            if (start < 0) {
                continue;   // 번호 줄, WEBVTT 머리말, NOTE 등
            }
            // 빈 줄 없이 다음 자막 번호가 바로 오는 경우: 번호 다음 줄이 시간 줄이면 번호로 본다
            if (CUE_NUMBER.matcher(line).matches() && i + 1 < lines.length
                    && TIMING.matcher(lines[i + 1].strip()).find()) {
                continue;
            }
            String clean = TAGS.matcher(line).replaceAll("").strip();
            if (clean.isEmpty()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(clean);
        }
        flush(cues, start, end, text);
        return cues;
    }

    private static void flush(List<Cue> cues, long start, long end, StringBuilder text) {
        if (start < 0 || text.isEmpty()) return;
        long safeEnd = Math.max(end, start);
        String t = text.length() > MAX_CUE_CHARS ? text.substring(0, MAX_CUE_CHARS) : text.toString();
        cues.add(new Cue(start, safeEnd, t));
    }

    /** "01:02:03,456" · "02:03.456" · "1:02:03.4" → ms */
    static long toMs(String stamp) {
        String[] main = stamp.replace(',', '.').split("\\.");
        String[] hms = main[0].split(":");
        long h = 0, m, s;
        if (hms.length == 3) {
            h = Long.parseLong(hms[0]);
            m = Long.parseLong(hms[1]);
            s = Long.parseLong(hms[2]);
        } else {
            m = Long.parseLong(hms[0]);
            s = Long.parseLong(hms[1]);
        }
        long ms = 0;
        if (main.length > 1) {
            String frac = (main[1] + "00").substring(0, 3);
            ms = Long.parseLong(frac);
        }
        return ((h * 60 + m) * 60 + s) * 1000 + ms;
    }
}
