package com.example.oops.lexicon;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 제작자가 모를 수 있는 맥락을 가진 표현 사전.
 *
 * 사전을 코드 밖(context-lexicon.json)에 둔 이유는
 * 개발 지식 없이도 항목을 늘릴 수 있게 하기 위해서다.
 *
 * **여기서 가장 중요한 건 안 잡는 쪽이다.**
 *
 * '7시', '수박', '포도', '초딩' 같은 말은 99%가 그냥 시간·과일·아이 이야기다.
 * 단어만 보고 카드를 만들면 영상 하나에 오탐이 수십 개 쏟아지고,
 * 제작자는 두 번째 영상부터 이 도구를 안 쓴다.
 *
 * 그래서 세 단계로 거른다.
 *   1. 표현이 나오는지
 *   2. 근처에 일반 용법 신호(suppressHints)가 있으면 버린다
 *   3. 그래도 애매하면 AI 에게 앞뒤 문장을 주고 물어본다  ← ContextValidator
 *
 * 이 클래스는 1~2단계까지만 한다. 3단계는 호출하는 쪽에서 한다.
 */
@Slf4j
@Component
public class ContextLexicon {

    /** 앞뒤 몇 글자 안에 있는 힌트까지 볼지 */
    private static final int HINT_WINDOW = 40;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private List<ContextLexiconEntry> entries = List.of();
    private List<Pattern> compiled = List.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("context-lexicon.json").getInputStream()) {
            LexiconFile file = jsonMapper.readValue(in, LexiconFile.class);
            entries = file.entries() == null ? List.of() : file.entries();

            compiled = entries.stream()
                    .map(e -> e.regex() == null || e.regex().isBlank()
                            ? null : Pattern.compile(e.regex()))
                    .toList();

            long core = entries.stream().filter(e -> e.triggerMode().isCore()).count();
            log.info("[lexicon] 맥락 사전 {}개 로드 (핵심 {}개, 안전망 {}개)",
                    entries.size(), core, entries.size() - core);

        } catch (Exception e) {
            // 사전을 못 읽어도 서버는 떠야 한다. 다른 분석기는 멀쩡하다.
            log.error("[lexicon] 사전을 읽지 못했습니다. 맥락 표현 탐지가 비활성됩니다.", e);
            entries = List.of();
            compiled = List.of();
        }
    }

    public int size() {
        return entries.size();
    }

    /**
     * 한 줄에서 걸리는 표현을 찾는다.
     *
     * suppressHints 에 걸리면 아예 돌려주지 않는다.
     * "7시에 만나기로 했어요" 는 여기서 끝난다. AI 를 부르지도 않는다.
     */
    public List<Match> match(String text) {
        if (text == null || text.isBlank() || entries.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.KOREAN);
        List<Match> matches = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            ContextLexiconEntry entry = entries.get(i);

            String hit = findHit(entry, compiled.get(i), text, lower);
            if (hit == null) {
                continue;
            }

            // 일반 용법 신호가 근처에 있으면 버린다.
            // 이 한 줄이 오탐의 대부분을 막는다.
            if (hasHint(lower, text, hit, entry.suppressHintsOrEmpty())) {
                log.debug("[lexicon] '{}' 는 일반 용법으로 보여 건너뜁니다 — {}", hit, text);
                continue;
            }

            boolean supported = hasHint(lower, text, hit, entry.contextHintsOrEmpty());
            matches.add(new Match(entry, hit, supported));
        }
        return matches;
    }

    private String findHit(ContextLexiconEntry entry, Pattern regex, String text, String lower) {
        if (regex != null) {
            var matcher = regex.matcher(text.trim());
            if (matcher.find()) {
                return matcher.group();
            }
        }
        for (String pattern : entry.patternsOrEmpty()) {
            if (pattern.startsWith("~")) {
                continue;   // 정규식으로 처리하는 항목의 표시용 이름
            }
            if (lower.contains(pattern.toLowerCase(Locale.KOREAN))) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 걸린 표현 근처에 힌트가 있는지.
     *
     * 문장 전체를 보지 않고 주변만 보는 이유는, 긴 문장에서 저 멀리 있는 단어가
     * 우연히 걸려 판단이 뒤집히는 걸 막기 위해서다.
     */
    private boolean hasHint(String lower, String original, String hit, List<String> hints) {
        if (hints.isEmpty()) {
            return false;
        }
        int at = lower.indexOf(hit.toLowerCase(Locale.KOREAN));
        if (at < 0) {
            at = 0;
        }
        int from = Math.max(0, at - HINT_WINDOW);
        int to = Math.min(lower.length(), at + hit.length() + HINT_WINDOW);
        String around = lower.substring(from, to);

        return hints.stream().anyMatch(h -> around.contains(h.toLowerCase(Locale.KOREAN)));
    }

    /**
     * 사전에 걸린 한 건.
     *
     * @param contextSupported 특수 용법 신호가 근처에 있었는지.
     *                         true 면 확신도를 조금 올린다.
     */
    public record Match(ContextLexiconEntry entry, String matchedText, boolean contextSupported) {

        public double score() {
            double base = entry.triggerMode().baseScore();
            return contextSupported ? Math.min(0.75, base + 0.15) : base;
        }
    }

    /**
     * JSON 최상위.
     *
     * _readme 는 파일을 여는 사람에게 보여줄 설명이다. 코드에서는 안 쓰지만
     * 필드로 받아둬야 "모르는 속성" 으로 파싱이 깨지지 않는다.
     */
    record LexiconFile(List<String> _readme, List<ContextLexiconEntry> entries) {}
}
