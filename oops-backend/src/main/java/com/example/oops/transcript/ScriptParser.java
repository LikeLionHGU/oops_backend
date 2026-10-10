package com.example.oops.transcript;

import com.example.oops.transcript.TranscriptProvider.TranscriptLine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 붙여넣은 유튜브 스크립트를 타임스탬프 대본으로 바꾼다.
 *
 * 유튜브 링크를 서버에서 내려받지 못하는 동안(데이터센터 IP 차단) 쓰는 입력 방식이다.
 * 사용자가 유튜브의 "스크립트 표시" 창에서 복사한 글을 그대로 붙여넣는다.
 *
 * 받는 모양
 *
 *   0:00                      ← 타임스탬프가 따로 한 줄
 *   아 아니 여기 중국 아니에요?
 *   0:03
 *   아니 그니까 이건 ...
 *
 *   0:00 아 아니 여기 ...      ← 같은 줄
 *   [1:02:03] 한 시간 지점     ← 대괄호
 *
 *   0:00 아 아니 ... 0:03 아니 그니까 ...   ← 줄바꿈이 사라진 한 줄 (한 줄 입력칸에 붙여넣은 경우)
 *
 * 함께 정리하는 것
 *   · 유튜브가 시각 옆에 붙이는 읽기용 문구 ("5초", "1분 5초", "1 minute, 5 seconds")
 *   · [음악] [박수] (웃음) 같은 소리 표시
 *   · "스크립트" 같은 창 제목 줄
 *
 * 시각이 하나도 없는 글(타임스탬프를 끄고 복사한 경우)도 받는다.
 * 그때는 줄·문장 단위로 나누고 글자 수로 시각을 추정한다. estimated() 로 알 수 있다.
 */
public final class ScriptParser {

    /** 결과 대본과 시각을 추정했는지 여부 */
    public record Result(List<TranscriptLine> lines, boolean estimated) {
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** 마지막 줄이 끝나는 시각(초, 올림). 줄이 없으면 null */
        public Integer durationSec() {
            if (lines.isEmpty()) return null;
            long end = lines.get(lines.size() - 1).endMs();
            return (int) Math.ceil(end / 1000.0);
        }
    }

    /**
     * 공백·줄 경계로 둘러싸인 시각. "0:05", "12:34", "1:02:03", "[0:05]".
     * 문장 속 "3:30에" 처럼 글자에 붙은 시각은 잡지 않는다.
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<![\\S])\\[?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\]?(?![\\S])");

    /** 시각 바로 뒤에 붙는 읽기용 문구 */
    private static final Pattern SPOKEN_TIME = Pattern.compile(
            "^\\s*(?:(?:\\d+\\s*시간\\s*)?(?:\\d+\\s*분\\s*)?\\d+\\s*초|\\d+\\s*시간(?:\\s*\\d+\\s*분)?|\\d+\\s*분"
                    + "|\\d+\\s+(?:hours?|minutes?|seconds?)(?:,?\\s+\\d+\\s+(?:hours?|minutes?|seconds?))*)(?=\\s|$)",
            Pattern.CASE_INSENSITIVE);

    /** 소리 표시. [음악] (웃음) 등 짧은 괄호 묶음 */
    private static final Pattern SOUND_TAG = Pattern.compile("[\\[(（]\\s*[^\\[\\]()（）]{1,10}\\s*[\\])）]");

    private static final java.util.Set<String> HEADER_LINES = java.util.Set.of(
            "스크립트", "transcript", "타임스탬프 표시", "타임스탬프 숨기기", "show transcript", "hide transcript");

    /** 시각 정보가 없을 때 글자당 추정 시간(ms). 한국어 말하기 속도 초당 5~6자 */
    static final long MS_PER_CHAR = 180;
    static final long MIN_LINE_MS = 1500;
    /** 시각이 있는 줄도 다음 시각까지 너무 길면 이 이상은 잡지 않는다 */
    static final long MAX_LINE_MS = 15_000;
    /** 시각 없는 글에서 한 줄이 이보다 길면 문장 단위로 나눈다 */
    static final int SPLIT_OVER_CHARS = 80;

    private ScriptParser() {
    }

    public static Result parse(String script) {
        if (script == null || script.isBlank()) return new Result(List.of(), false);
        String text = script.replace("﻿", "").replace(' ', ' ')
                .replace("\r\n", "\n").replace('\r', '\n');

        List<long[]> marks = new ArrayList<>();   // [시각ms, 글 시작 위치, 시각 시작 위치]
        Matcher m = TIMESTAMP.matcher(text);
        while (m.find()) {
            Long ms = toMs(m.group(1));
            if (ms != null) marks.add(new long[]{ms, m.end(), m.start()});
        }

        // 시각이 둘 이상이고 순서가 대체로 맞으면 시각 기준으로 나눈다
        if (marks.size() >= 2 && mostlyIncreasing(marks)) {
            return new Result(byTimestamps(text, marks), false);
        }
        return new Result(estimate(text), true);
    }

    private static List<TranscriptLine> byTimestamps(String text, List<long[]> marks) {
        List<long[]> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            int from = (int) marks.get(i)[1];
            int to = i + 1 < marks.size() ? (int) marks.get(i + 1)[2] : text.length();
            String body = clean(text.substring(from, Math.max(from, to)));
            if (body.isEmpty()) continue;
            starts.add(marks.get(i));
            texts.add(body);
        }

        List<TranscriptLine> lines = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            long start = starts.get(i)[0];
            String body = texts.get(i);
            long guess = Math.max(MIN_LINE_MS, body.length() * MS_PER_CHAR);
            long end;
            if (i + 1 < starts.size() && starts.get(i + 1)[0] > start) {
                end = Math.min(starts.get(i + 1)[0], start + Math.max(guess, MAX_LINE_MS));
            } else {
                end = start + Math.min(guess, MAX_LINE_MS);
            }
            lines.add(new TranscriptLine(start, end, body));
        }
        return lines;
    }

    /** 시각이 없는 글: 줄 → (길면) 문장으로 나누고 글자 수로 시각을 매긴다 */
    private static List<TranscriptLine> estimate(String text) {
        List<String> pieces = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = clean(raw);
            if (line.isEmpty()) continue;
            if (line.length() <= SPLIT_OVER_CHARS) {
                pieces.add(line);
                continue;
            }
            for (String sentence : line.split("(?<=[.?!。？！…])\\s+")) {
                String s = sentence.trim();
                if (s.isEmpty()) continue;
                // 문장부호가 없는 자동 생성 스크립트는 한 덩어리가 길다. 띄어쓰기 기준으로 적당히 자른다
                while (s.length() > SPLIT_OVER_CHARS) {
                    int cut = s.lastIndexOf(' ', SPLIT_OVER_CHARS);
                    if (cut <= 0) cut = SPLIT_OVER_CHARS;
                    pieces.add(s.substring(0, cut).trim());
                    s = s.substring(cut).trim();
                }
                if (!s.isEmpty()) pieces.add(s);
            }
        }
        List<TranscriptLine> lines = new ArrayList<>();
        long t = 0;
        for (String p : pieces) {
            long d = Math.max(MIN_LINE_MS, p.length() * MS_PER_CHAR);
            lines.add(new TranscriptLine(t, t + d, p));
            t += d;
        }
        return lines;
    }

    /** 한 구간의 글을 다듬는다: 읽기용 시각 문구·소리 표시·창 제목 제거, 공백 정리 */
    static String clean(String body) {
        String s = body.strip();
        s = SPOKEN_TIME.matcher(s).replaceFirst("");
        StringBuilder kept = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || HEADER_LINES.contains(l.toLowerCase(java.util.Locale.ROOT))) continue;
            l = SPOKEN_TIME.matcher(l).replaceFirst("").strip();
            if (l.isEmpty()) continue;
            if (!kept.isEmpty()) kept.append(' ');
            kept.append(l);
        }
        String out = SOUND_TAG.matcher(kept.toString()).replaceAll(" ");
        return out.replaceAll("\\s+", " ").strip();
    }

    private static boolean mostlyIncreasing(List<long[]> marks) {
        int ok = 0;
        for (int i = 1; i < marks.size(); i++) {
            if (marks.get(i)[0] >= marks.get(i - 1)[0]) ok++;
        }
        return ok >= (marks.size() - 1) * 0.8;
    }

    /** "1:02:03" / "12:34" → ms. 분·초가 60 이상이면 시각이 아니다 */
    static Long toMs(String stamp) {
        String[] p = stamp.split(":");
        try {
            long h = 0, mi, s;
            if (p.length == 3) {
                h = Long.parseLong(p[0]);
                mi = Long.parseLong(p[1]);
                s = Long.parseLong(p[2]);
            } else {
                mi = Long.parseLong(p[0]);
                s = Long.parseLong(p[1]);
            }
            if (s >= 60 || (p.length == 3 && mi >= 60)) return null;
            return ((h * 60 + mi) * 60 + s) * 1000;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
