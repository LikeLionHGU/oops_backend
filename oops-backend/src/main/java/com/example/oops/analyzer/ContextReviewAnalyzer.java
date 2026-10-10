package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.lexicon.ContextLexicon;
import com.example.oops.review.ReviewPrompts;
import com.example.oops.review.TaxonomyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 맥락 검토 — 8유형, 2단계. (2026-10 고도화)
 *
 * speech-review(발언 검토)와 screen-text-review(화면 자막 검토), context-lexicon(사전 맥락 확인)을
 * 한 분석기로 합쳤다. 유형은 TaxonomyType 의 여덟 가지다.
 *
 *   1차 선별  대본(발언 + 발언과 다른 자막)을 창 단위로 훑어 의심되는 줄과 유형 후보를 표시한다.
 *             프롬프트가 짧다. 놓치지 않는 것이 목표다.
 *   사전      맥락 사전에 걸린 줄은 1차 선별 결과와 상관없이 후보에 넣는다.
 *             사전은 유형이 아니라 탐지 도구다. 항목마다 보낼 유형(riskType)이 적혀 있다.
 *   2차 검증  후보마다 유형 전용 프롬프트로 다시 판단한다. 정의·확인 항목·예시·반례가 다 들어 있다.
 *             같은 유형 후보를 여러 개 묶어 한 번에 보낸다.
 *
 * **1차에서 놓친 것은 2차에서 되살릴 수 없다.** 1차는 일부러 너그럽게 잡는다.
 * 대신 1차가 폭주하면 2차 호출이 터지므로 창당 후보 수에 상한을 둔다.
 *
 * 한 줄이 여러 유형에 걸리면 카드는 한 장이다. 주 유형은 TaxonomyType 의 우선순위로 고르고
 * 나머지는 이유에 "함께 해당" 으로 적는다. 미성년자에 대한 성적 의미 부여는 항상 최우선이다.
 *
 * 사실 확인(연도·숫자·이름)은 여기서 하지 않는다. entity-check 가 자료를 찾아 대조한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextReviewAnalyzer implements ContentAnalyzer {

    /** 2차 검증에 앞뒤로 붙여 줄 문맥 줄 수 */
    static final int CONTEXT_LINES = 2;

    /** 한 줄에 대해 2차 검증을 돌릴 최대 유형 수. 우선순위가 높은 둘만 본다. */
    static final int MAX_TYPES_PER_LINE = 2;

    /** 친근한 놀림의 점수 상한. 잡되 맨 아래로 보낸다. */
    static final double TEASING_SCORE_CAP = 0.35;

    /** 감탄형 욕설의 점수 상한. */
    static final double EXCLAMATION_SCORE_CAP = 0.3;

    /** 미성년자 관련 성적 의미 부여는 항상 이 점수 이상 */
    static final double MINOR_SCORE = 0.95;

    private final OpenAiClient openAiClient;
    private final ContextLexicon lexicon;
    private final OopsProperties properties;

    @Override
    public String key() {
        return "context-review";
    }

    @Override
    public String displayName() {
        return "맥락 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return (context.hasTranscript() || context.hasScreenText()) && openAiClient.isEnabled();
    }

    /** 1차 선별이 대본 길이에 비례해 호출된다. */
    @Override
    public boolean scalesWithLength() {
        return true;
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<Line> lines = buildLines(context);
        if (lines.isEmpty()) {
            return List.of();
        }
        OopsProperties.ContextReview cfg = properties.analysis().contextReviewOrDefault();

        // 1. 1차 선별
        Map<Integer, Candidate> candidates = new TreeMap<>();
        int window = cfg.windowSizeOrDefault();
        int stride = Math.max(1, window - cfg.overlapOrDefault());
        int windows = 0;
        for (int start = 0; start < lines.size(); start += stride) {
            int end = Math.min(start + window, lines.size());
            screen(lines.subList(start, end), start, cfg.maxCandidatesPerWindowOrDefault(), candidates);
            windows++;
            if (end == lines.size()) break;
        }
        int screened = candidates.size();

        // 2. 사전에 걸린 줄은 무조건 후보로. 맥락 확인이 필요 없는 항목은 룰 분석기가 바로 올린다.
        for (Line line : lines) {
            for (ContextLexicon.Match match : lexicon.match(line.text())) {
                if (!match.entry().requiresContextCheck()) continue;
                Candidate c = candidates.computeIfAbsent(line.index(), i -> new Candidate(line));
                c.types.add(match.entry().taxonomyType());
                c.lexiconNotes.add("'%s' — %s".formatted(match.matchedText(), match.entry().reason()));
            }
        }

        // 3. 2차 검증: 유형별로 묶어서
        Map<TaxonomyType, List<Candidate>> byType = new EnumMap<>(TaxonomyType.class);
        for (Candidate c : candidates.values()) {
            for (TaxonomyType t : TaxonomyType.byPrecedence(new ArrayList<>(c.types))
                    .stream().limit(MAX_TYPES_PER_LINE).toList()) {
                byType.computeIfAbsent(t, k -> new ArrayList<>()).add(c);
            }
        }
        int batch = cfg.verifyBatchSizeOrDefault();
        int verifyCalls = 0;
        for (Map.Entry<TaxonomyType, List<Candidate>> e : byType.entrySet()) {
            List<Candidate> list = e.getValue();
            for (int i = 0; i < list.size(); i += batch) {
                verify(e.getKey(), list.subList(i, Math.min(i + batch, list.size())), lines, context);
                verifyCalls++;
            }
        }

        // 4. 줄마다 카드 한 장
        List<RiskFinding> findings = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            RiskFinding f = toFinding(context, c);
            if (f != null) findings.add(f);
        }

        log.info("[context-review] videoId={} 줄={} (자막 {}줄) 창={} 1차후보={} 사전추가={} 2차호출={} 결과={}",
                context.video().getId(), lines.size(),
                lines.stream().filter(Line::caption).count(), windows,
                screened, candidates.size() - screened, verifyCalls, findings.size());
        return findings;
    }

    // ─────────────────────────────────────────────────────────────────
    // 입력 만들기
    // ─────────────────────────────────────────────────────────────────

    /**
     * 발언 전부 + 발언과 내용이 다른 자막 줄을 시간순으로 합친다.
     * 받아 적은 자막은 발언으로 이미 보므로 넣지 않는다 (CaptionAlignment).
     */
    List<Line> buildLines(AnalysisContext context) {
        List<Line> raw = new ArrayList<>();
        if (context.hasTranscript()) {
            for (TranscriptSegment s : context.transcript()) {
                if (s.getText() == null || s.getText().isBlank()) continue;
                raw.add(new Line(0, false, s.getText().trim(), s.getStartMs(), s.getEndMs(), null));
            }
        }
        if (context.hasScreenText()) {
            List<ScreenText> captions = CaptionAlignment.distinctFromSpeech(
                    context.screenTexts(), context.hasTranscript() ? context.transcript() : List.of());
            for (ScreenText c : captions) {
                if (c.getText() == null || c.getText().isBlank()) continue;
                raw.add(new Line(0, true, c.getText().trim(), c.getStartMs(), c.getEndMs(), c));
            }
        }
        raw.sort(Comparator.comparingLong(Line::startMs).thenComparing(Line::caption));
        List<Line> lines = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Line l = raw.get(i);
            lines.add(new Line(i, l.caption(), l.text(), l.startMs(), l.endMs(), l.captionRef()));
        }
        return lines;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1차 선별
    // ─────────────────────────────────────────────────────────────────

    private void screen(List<Line> window, int offset, int maxPerWindow, Map<Integer, Candidate> into) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < window.size(); i++) {
            Line l = window.get(i);
            // 시각은 넣지 않는다. 모델은 번호만 돌려주고 시각은 코드가 번호로 찾는다.
            prompt.append("[%d] (%s) %s%n".formatted(i, l.caption() ? "자막" : "발언", l.text()));
        }

        ScreenResult result = openAiClient
                .completeAsJson(ReviewPrompts.SCREEN_PROMPT, prompt.toString(), ScreenResult.class)
                .orElse(null);
        if (result == null || result.candidates() == null) return;

        // 상한을 넘으면 심각한 유형부터 남긴다. 모델이 돌려준 순서대로 자르면
        // 바쁜 구간에서 앞쪽의 가벼운 말투 후보 때문에 뒤쪽 피해자 조롱이 잘릴 수 있다.
        List<ScreenItem> ordered = result.candidates().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(ContextReviewAnalyzer::mostSevere))
                .toList();
        int taken = 0;
        for (ScreenItem item : ordered) {
            if (taken >= maxPerWindow) {
                log.debug("[context-review] 한 창에서 후보가 {}건을 넘어 나머지를 버립니다", maxPerWindow);
                break;
            }
            int local = item.index() == null ? -1 : item.index();
            if (local < 0 || local >= window.size()) continue;
            List<TaxonomyType> types = item.types() == null ? List.of()
                    : item.types().stream().map(TaxonomyType::parse)
                    .flatMap(Optional::stream).distinct().toList();
            if (types.isEmpty()) continue;

            Line line = window.get(local);
            Candidate c = into.computeIfAbsent(line.index(), k -> new Candidate(line));
            c.types.addAll(types);
            if (item.cue() != null && !item.cue().isBlank()) c.cues.add(item.cue().trim());
            taken++;
        }
    }

    /** 후보가 고른 유형 중 가장 앞선 우선순위. 알 수 없는 유형뿐이면 맨 뒤. */
    private static int mostSevere(ScreenItem item) {
        if (item.types() == null) return Integer.MAX_VALUE;
        return item.types().stream().map(TaxonomyType::parse).flatMap(Optional::stream)
                .mapToInt(TaxonomyType::precedence).min().orElse(Integer.MAX_VALUE);
    }

    // ─────────────────────────────────────────────────────────────────
    // 2차 검증
    // ─────────────────────────────────────────────────────────────────

    private void verify(TaxonomyType type, List<Candidate> batch, List<Line> lines, AnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            Candidate c = batch.get(i);
            Line target = c.line;
            prompt.append("[%d]".formatted(i));
            if (!c.cues.isEmpty()) {
                prompt.append(" 1차 표시 이유: ").append(String.join(", ", c.cues));
            }
            prompt.append('\n');
            for (int j = Math.max(0, target.index() - CONTEXT_LINES); j < target.index(); j++) {
                prompt.append("  앞: ").append(label(lines.get(j))).append('\n');
            }
            prompt.append("  ▶ ").append(label(target)).append('\n');
            for (int j = target.index() + 1; j <= Math.min(lines.size() - 1, target.index() + CONTEXT_LINES); j++) {
                prompt.append("  뒤: ").append(label(lines.get(j))).append('\n');
            }
            if (target.caption()) {
                // 자막이면 같은 시간의 발언을 보여 준다. 편집자가 더한 의미인지 판단하는 데 쓴다.
                String speech = target.captionRef() == null || !context.hasTranscript() ? ""
                        : CaptionAlignment.overlappingSpeechText(target.captionRef(), context.transcript());
                if (!speech.isBlank()) prompt.append("  같은 시간 발언: ").append(speech).append('\n');
            }
            for (String note : c.lexiconNotes) {
                prompt.append("  사전 정보: ").append(note).append('\n');
            }
            prompt.append('\n');
        }

        VerifyResult result = openAiClient
                .completeAsJson(ReviewPrompts.verifyPrompt(type), prompt.toString(), VerifyResult.class)
                .orElse(null);
        if (result == null || result.results() == null) return;

        for (VerifyItem item : result.results()) {
            int id = item.id() == null ? -1 : item.id();
            if (id < 0 || id >= batch.size()) continue;
            batch.get(id).verdicts.put(type, item);
        }
    }

    private static String label(Line l) {
        return "(%s) %s".formatted(l.caption() ? "자막" : "발언", l.text());
    }

    // ─────────────────────────────────────────────────────────────────
    // 카드 만들기
    // ─────────────────────────────────────────────────────────────────

    /**
     * 한 줄의 검증 결과를 카드 한 장으로. 해당 없으면 null.
     *
     * FLAG 가 여럿이면 우선순위가 높은 유형이 주 유형이다.
     * 미성년자 표시가 붙은 결과가 있으면 그것이 무조건 주 유형이다.
     */
    RiskFinding toFinding(AnalysisContext context, Candidate c) {
        List<Map.Entry<TaxonomyType, VerifyItem>> flagged = c.verdicts.entrySet().stream()
                .filter(e -> e.getValue() != null && "FLAG".equalsIgnoreCase(trim(e.getValue().verdict())))
                .filter(e -> passesGates(e.getKey(), e.getValue()))
                .sorted(Comparator
                        .comparing((Map.Entry<TaxonomyType, VerifyItem> e) -> !Boolean.TRUE.equals(e.getValue().minor()))
                        .thenComparingInt(e -> e.getKey().precedence()))
                .toList();
        if (flagged.isEmpty()) return null;

        TaxonomyType primary = flagged.get(0).getKey();
        VerifyItem item = flagged.get(0).getValue();

        // 함께 해당하는 유형: 다른 FLAG + 모델이 also 로 적은 것
        LinkedHashSet<TaxonomyType> also = new LinkedHashSet<>();
        flagged.stream().skip(1).forEach(e -> also.add(e.getKey()));
        if (item.also() != null) {
            item.also().stream().map(TaxonomyType::parse).flatMap(Optional::stream).forEach(also::add);
        }
        also.remove(primary);

        double score = item.score() == null ? 0.5 : Math.max(0.0, Math.min(1.0, item.score()));
        boolean minor = Boolean.TRUE.equals(item.minor());
        boolean teasing = Boolean.TRUE.equals(item.teasing());
        boolean exclamation = primary == TaxonomyType.INSULT && isExclamation(item.subtype());

        StringBuilder reason = new StringBuilder();
        String subtype = trim(item.subtype());
        if (!subtype.isEmpty()) reason.append('[').append(subtype).append("] ");
        reason.append(trim(item.reason()));
        if (!also.isEmpty()) {
            reason.append(" 함께 해당: ")
                    .append(also.stream().map(TaxonomyType::label).collect(Collectors.joining(", ")))
                    .append('.');
        }

        if (minor) {
            score = Math.max(score, MINOR_SCORE);
            reason.append(" 미성년자가 관련된 장면입니다.");
        } else if (teasing) {
            score = Math.min(score, TEASING_SCORE_CAP);
            reason.append(" 출연자끼리 주고받는 장난으로 보입니다.");
        }
        if (exclamation && !minor) {
            score = Math.min(score, EXCLAMATION_SCORE_CAP);
        }

        Line line = c.line;
        String target = trim(item.target());
        RiskFinding.RiskFindingBuilder builder = RiskFinding.builder()
                .video(context.video())
                .category(primary.category())
                .source(EvidenceSource.SUBTITLE)
                .score(score)
                .startMs(line.startMs())
                .endMs(line.endMs())
                .reason(reason.toString().trim())
                .target(target.isEmpty() ? null : target);
        if (line.caption()) {
            builder.eventType(TimelineEventType.CAPTION)
                    .captionText(line.text())
                    .speechText(context.hasTranscript() && line.captionRef() != null
                            ? CaptionAlignment.overlappingSpeechText(line.captionRef(), context.transcript())
                            : null)
                    .frame(line.captionRef() == null ? null : line.captionRef().getFrame());
        } else {
            builder.eventType(TimelineEventType.SPEECH).text(line.text());
        }
        return builder.build();
    }

    /**
     * 모델이 FLAG 라고 해도 코드가 한 번 더 막는다. 프롬프트는 부탁이고, 여기가 문이다.
     *   · 이유가 비었거나 알맹이가 없으면 버린다 (VagueReasonFilter)
     *   · 대상이 필요한 유형인데 대상을 못 적었거나 분류명만 적었으면 버린다
     *     (단 욕설 중 감탄형은 대상이 없어도 된다)
     */
    static boolean passesGates(TaxonomyType type, VerifyItem item) {
        String reason = trim(item.reason());
        if (!VagueReasonFilter.isUseful(reason)) return false;

        boolean needTarget = type.targetRequired()
                || (type == TaxonomyType.INSULT && !isExclamation(item.subtype()));
        if (needTarget && SpeechReviewAnalyzer.isPlaceholderTarget(item.target())) {
            return Boolean.TRUE.equals(item.minor());   // 미성년자 관련은 대상이 애매해도 남긴다
        }
        return true;
    }

    static boolean isExclamation(String subtype) {
        return subtype != null && subtype.contains("감탄");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ─────────────────────────────────────────────────────────────────
    // 자료형
    // ─────────────────────────────────────────────────────────────────

    /** 검토 대상 한 줄. index 는 합친 목록 안에서의 번호다. */
    record Line(int index, boolean caption, String text, long startMs, long endMs, ScreenText captionRef) {}

    /** 2차 검증으로 보낼 후보 한 줄. */
    static final class Candidate {
        final Line line;
        final LinkedHashSet<TaxonomyType> types = new LinkedHashSet<>();
        final List<String> cues = new ArrayList<>();
        final List<String> lexiconNotes = new ArrayList<>();
        final Map<TaxonomyType, VerifyItem> verdicts = new EnumMap<>(TaxonomyType.class);

        Candidate(Line line) {
            this.line = line;
        }
    }

    record ScreenResult(List<ScreenItem> candidates) {}

    record ScreenItem(Integer index, List<String> types, String cue) {}

    record VerifyResult(List<VerifyItem> results) {}

    record VerifyItem(Integer id, String verdict, String subtype, String target, String reason,
                      Double score, List<String> also, Boolean teasing, Boolean minor) {}
}
