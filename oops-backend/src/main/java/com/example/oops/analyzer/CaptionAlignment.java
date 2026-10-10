package com.example.oops.analyzer;

import com.example.oops.domain.ScreenText;
import com.example.oops.domain.TranscriptSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 자막 줄과 발언을 시간축에서 맞춰 보는 도구. (2026-10 고도화)
 *
 * SRT 자막은 대부분 출연자 말을 받아 적은 것이다. 그런 줄을 발언과 따로 한 번 더 검토하면
 * 같은 내용을 두 번 보내는 셈이라 토큰이 두 배로 들고 카드도 두 장 나온다.
 * 그래서 **발언과 내용이 다른 자막 줄만** 따로 검토한다.
 * 예능 자막, 편집자가 덧붙인 문구, 요약하면서 뜻이 달라진 자막이 여기 남는다.
 */
public final class CaptionAlignment {

    /** 자막 앞뒤로 이만큼(ms)은 같은 장면으로 본다. 자막은 말보다 조금 늦게 뜨거나 오래 남는다. */
    static final long SLACK_MS = 700;

    /**
     * 이 이상 겹치면 "발언을 받아 적은 자막" 으로 본다.
     *
     * 두 글자 단위 겹침 비율이라 음성 인식이 한두 글자 틀려도 받아 적은 자막으로 잡힌다.
     * 자막이 짧으면(5자 미만) 우연히 겹치기 쉬워서 따로 다룬다 — mirrorsSpeech 참고.
     */
    static final double MIRROR_THRESHOLD = 0.7;

    private CaptionAlignment() {
    }

    /** 자막 구간과 겹치는 발언들. */
    public static List<TranscriptSegment> overlapping(ScreenText caption, List<TranscriptSegment> transcript) {
        List<TranscriptSegment> result = new ArrayList<>();
        if (caption == null || transcript == null) return result;
        long from = caption.getStartMs() - SLACK_MS;
        long to = caption.getEndMs() + SLACK_MS;
        for (TranscriptSegment s : transcript) {
            if (s.getEndMs() >= from && s.getStartMs() <= to) {
                result.add(s);
            }
        }
        return result;
    }

    /** 겹치는 발언을 이어 붙인 글. 없으면 빈 문자열. */
    public static String overlappingSpeechText(ScreenText caption, List<TranscriptSegment> transcript) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment s : overlapping(caption, transcript)) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(s.getText());
        }
        return sb.toString();
    }

    /**
     * 자막이 발언을 받아 적은 것인지.
     *
     * 자막 내용(이어진 두 글자 단위) 중 몇 %가 같은 시간의 발언에 들어 있는지를 본다.
     * 기준은 자막 길이다. 발언이 길고 자막이 그 일부만 적어도 받아 적은 자막이다.
     */
    public static boolean mirrorsSpeech(ScreenText caption, List<TranscriptSegment> transcript) {
        String speech = overlappingSpeechText(caption, transcript);
        if (speech.isBlank()) return false;
        String c = normalize(caption.getText());
        if (c.length() < 5) {
            // 짧은 자막은 발언 안에 그대로 들어 있을 때만 받아 적은 것으로 본다
            return normalize(speech).contains(c);
        }
        return coverage(caption.getText(), speech) >= MIRROR_THRESHOLD;
    }

    /** 발언과 내용이 다른 자막 줄만. */
    public static List<ScreenText> distinctFromSpeech(List<ScreenText> captions, List<TranscriptSegment> transcript) {
        if (captions == null || captions.isEmpty()) return List.of();
        if (transcript == null || transcript.isEmpty()) return captions;
        return captions.stream().filter(c -> !mirrorsSpeech(c, transcript)).toList();
    }

    /**
     * a 의 내용 중 b 에도 있는 비율. 0 이면 완전히 다름, 1 이면 a 가 전부 b 안에 있음.
     *
     * 글자 하나씩이 아니라 **이어진 두 글자(바이그램)** 단위로 센다.
     * 글자 단위로 세면 순서를 무시해서, 긴 발언 옆에 뜬 "이 사람 진짜 미친놈이네" 같은 자막도
     * 흔한 글자가 다 들어 있다는 이유로 받아 적은 자막으로 잘못 분류됐다 (0.8 나옴).
     * 두 글자 단위면 '미친', '친놈' 이 발언에 실제로 이어서 나와야 겹친 것으로 친다.
     * 음성 인식이 한두 글자 틀리면 그 주변 두세 쌍만 빠지므로 받아 적은 자막은 여전히 높게 나온다.
     */
    public static double coverage(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        List<String> xs = grams(x);
        Map<String, Integer> counts = new HashMap<>();
        for (String g : grams(y)) counts.merge(g, 1, Integer::sum);
        int common = 0;
        for (String g : xs) {
            Integer left = counts.get(g);
            if (left != null && left > 0) {
                counts.put(g, left - 1);
                common++;
            }
        }
        return (double) common / xs.size();
    }

    /** 이어진 두 글자 목록. 한 글자짜리는 그 글자 하나. */
    private static List<String> grams(String s) {
        List<String> out = new ArrayList<>();
        if (s.length() == 1) {
            out.add(s);
            return out;
        }
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    /** 두 구간이 겹치는 길이(ms). 안 겹치면 0 이하. */
    static long overlapMs(long aStart, long aEnd, long bStart, long bEnd) {
        return Math.min(aEnd, bEnd) - Math.max(aStart, bStart);
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}…·“”‘’「」]", "");
    }
}
