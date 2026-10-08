package com.example.oops.analyzer;

import com.example.oops.domain.*;
import com.example.oops.lexicon.ContextLexicon;
import com.example.oops.lexicon.ContextValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 제작자가 모를 수 있는 맥락을 가진 표현을 찾는다.
 *
 * 이게 이 도구의 핵심입니다.
 * '13분 20초에 욕설이 있습니다' 는 편집자도 안다. 알면서 넣은 것일 수도 있다.
 * 하지만 '7시', '포도', '수박' 이 특정 맥락에서 다른 뜻으로 쓰인다는 건
 * 모르면 그냥 지나친다. 그게 진짜 사각지대다.
 *
 * 흐름:
 *   대본·화면글자
 *     ↓ 사전 매칭 (일반·특수 용법 신호를 각각 기록)
 *   시간대와 맥락 근거를 고려해 고른 후보
 *     ↓ 앞뒤 줄 붙여서 AI 에게 한 번에 확인
 *   LITERAL·QUOTATION → 버림
 *   CONTEXTUAL·AMBIGUOUS → 검토 후보
 *
 * 사전에 있다고 바로 카드를 만들지 않는 것이 이 분석기의 전부다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextLexiconAnalyzer implements ContentAnalyzer {

    /** 한 영상에서 확인할 최대 건수. 넘으면 비용도 화면도 감당이 안 된다 */
    private static final int MAX_MATCHES = 24;

    /** AI 문구가 사전 문구와 이만큼 겹치면 같은 말로 보고 안 붙인다 */
    private static final double SAME_MEANING = 0.75;

    private final ContextLexicon lexicon;
    private final ContextValidator validator;
    private final ThreadLocal<String> coverageNotice = new ThreadLocal<>();

    @Override
    public String key() {
        return "context-lexicon";
    }

    @Override
    public String displayName() {
        return "낯선 맥락 표현 확인";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        // AI 가 없으면 앞뒤 맥락을 가릴 수 없다.
        // 그 상태로 사전만 돌리면 "7시에 만나요" 가 전부 카드가 된다. 차라리 안 도는 게 낫다.
        return lexicon.size() > 0 && validator.isEnabled()
                && (context.hasTranscript() || context.hasScreenText());
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        coverageNotice.remove();
        List<Line> lines = collectLines(context);
        List<Candidate> allCandidates = new ArrayList<>();
        int limitedContextCandidates = 0;

        // 1단계 — 사전 매칭. 일반 용법 신호와 특수 맥락 신호가 충돌하면 보존한다.
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            for (ContextLexicon.Match match : lexicon.match(line.text())) {
                // 맥락을 안 봐도 되는 항목은 CommunitySlangRules 가 맡는다.
                // 그쪽은 AI 키가 없어도 돌기 때문에 안전망 역할을 한다.
                if (!match.entry().requiresContextCheck()) {
                    continue;
                }
                // 일반 용법 신호만 있고 특수 맥락 신호가 없으면 기존처럼 빠르게 제외한다.
                // 두 신호가 충돌할 때만 AI 문맥 확인으로 넘겨 불필요한 호출을 억제한다.
                if (match.commonUsageSupported() && !match.contextSupported()) {
                    continue;
                }
                ReviewInput.Window window = context.reviewInput().contextFor(line.inputId());
                if (window.omittedSegments() > 0) limitedContextCandidates++;
                allCandidates.add(new Candidate(allCandidates.size(), line, match,
                        contextText(window.before()), contextText(window.after()),
                        contextText(window.related())));
            }
        }

        if (allCandidates.isEmpty()) {
            log.info("[lexicon] videoId={} 걸린 표현 없음", context.video().getId());
            return List.of();
        }

        List<Candidate> candidates = selectAcrossTimeline(allCandidates);
        if (candidates.size() < allCandidates.size()) {
            coverageNotice.set("맥락 사전 후보 %d건 중 %d건을 시간대별로 골라 확인했습니다. 나머지 %d건은 확인하지 못했습니다."
                    .formatted(allCandidates.size(), candidates.size(), allCandidates.size() - candidates.size()));
        }
        if (limitedContextCandidates > 0) {
            String notice = "일부 맥락 사전 후보의 주변 문맥이 입력 한도로 제한되었습니다. 그룹별 최대 8구간·4,000코드포인트를 확인합니다.";
            coverageNotice.set(coverageNotice.get() == null ? notice : coverageNotice.get() + " " + notice);
        }

        // 2단계 — 앞뒤 맥락을 봐야 하는 것만 AI 에게 묻는다
        List<ContextValidator.Request> toValidate = candidates.stream()
                .filter(c -> c.match().entry().requiresContextCheck())
                .map(c -> new ContextValidator.Request(
                        c.index(), c.match().matchedText(),
                        c.match().entry().reason(),
                        c.line().type() == TimelineEventType.SPEECH ? "음성 STT 대본" : "화면 OCR 텍스트",
                        c.before(), c.line().text(), c.after(), c.relatedText()))
                .toList();

        Map<Integer, ContextValidator.Verdict> verdicts = validator.validate(toValidate);

        // 3단계 — 올릴 것만 남긴다
        List<RiskFinding> findings = new ArrayList<>();
        int dropped = 0;

        for (Candidate c : candidates) {
            ContextValidator.Verdict verdict = verdicts.get(c.index());

            // 물어봤는데 답을 못 받았으면 올리지 않는다.
            // 확인 안 된 것을 확인된 것처럼 보여주면 안 된다.
            if (verdict == null) {
                dropped++;
                continue;
            }
            // 일반 용법이거나 인용이면 버린다. 여기서 오탐 대부분이 걸러진다.
            if (!verdict.worthReporting()) {
                log.debug("[lexicon] '{}' {} 로 판단해 제외 — {}",
                        c.match().matchedText(), verdict.verdict(), c.line().text());
                dropped++;
                continue;
            }
            findings.add(build(context, c, verdict));
        }

        log.info("[lexicon] videoId={} 전체매칭 {}건 → 확인요청 {}건 → 후보 {}건 (제외 {}건, 미확인 {}건)",
                context.video().getId(), allCandidates.size(), toValidate.size(),
                findings.size(), dropped, allCandidates.size() - candidates.size());
        return findings;
    }

    @Override
    public Optional<String> consumeCoverageNotice(AnalysisContext context) {
        String notice = coverageNotice.get();
        coverageNotice.remove();
        return Optional.ofNullable(notice);
    }

    private RiskFinding build(AnalysisContext context, Candidate c,
                              ContextValidator.Verdict verdict) {
        var entry = c.match().entry();

        // 애매하다고 답한 건은 더 낮춘다. 확신 없는 걸 위로 올리면 안 된다.
        double score = c.match().score();
        if (verdict != null && verdict.isAmbiguous()) {
            score = Math.max(0.2, score - 0.15);
        }

        // 사전 문구와 AI 문구가 같은 말이면 하나만 남긴다.
        // 둘 다 붙이면 "커뮤니티 말투로 읽히기도 합니다. 커뮤니티 말투로 읽힐 수 있습니다."
        // 처럼 같은 문장이 두 번 나온다. 읽는 사람이 신뢰를 잃는다.
        String reason = verdict != null && verdict.isAmbiguous()
                ? "이 표현은 알려진 맥락과 관련될 수 있지만, 현재 구간만으로는 특수한 의미로 사용됐는지 판단하기 어렵습니다."
                : entry.reason();
        String note = verdict == null ? null : verdict.note();
        if (note != null && !note.isBlank() && addsSomething(reason, note)) {
            reason = reason + " " + note.trim();
        }

        String target = verdict != null && verdict.target() != null && !verdict.target().isBlank()
                ? verdict.target() : c.match().matchedText();

        Line line = c.line();
        RiskFinding.RiskFindingBuilder builder = RiskFinding.builder()
                .video(context.video())
                .eventType(line.type())
                .category(RiskCategory.UNFAMILIAR_CONTEXT)
                .source(line.type() == TimelineEventType.SPEECH
                        ? EvidenceSource.SUBTITLE : EvidenceSource.VISION)
                .score(score)
                .startMs(line.startMs())
                .endMs(line.endMs())
                .reason(reason)
                .target(target)
                .frame(line.frame());

        if (line.type() == TimelineEventType.SPEECH) {
            builder.text(line.text());
        } else {
            builder.captionText(line.text());
        }
        return builder.build();
    }

    /**
     * AI 가 덧붙인 말이 새 정보인지.
     *
     * 사전 문구를 조금 바꿔 되풀이한 것이면 붙이지 않는다.
     * 글자 단위로 보는 이유는 어순이나 어미만 바뀐 경우를 잡기 위해서다.
     */
    private boolean addsSomething(String reason, String note) {
        String a = reason.replaceAll("[^가-힣a-zA-Z0-9]", "");
        String b = note.replaceAll("[^가-힣a-zA-Z0-9]", "");
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }

        Map<Character, Integer> counts = new java.util.HashMap<>();
        for (char c : a.toCharArray()) counts.merge(c, 1, Integer::sum);

        int common = 0;
        for (char c : b.toCharArray()) {
            Integer left = counts.get(c);
            if (left != null && left > 0) {
                counts.put(c, left - 1);
                common++;
            }
        }
        return (double) common / b.length() < SAME_MEANING;
    }

    private List<Candidate> selectAcrossTimeline(List<Candidate> candidates) {
        if (candidates.size() <= MAX_MATCHES) return candidates;

        List<Candidate> selected = new ArrayList<>(MAX_MATCHES);
        for (int bucket = 0; bucket < MAX_MATCHES; bucket++) {
            int from = bucket * candidates.size() / MAX_MATCHES;
            int to = (bucket + 1) * candidates.size() / MAX_MATCHES;
            Candidate best = candidates.subList(from, to).stream()
                    .max(java.util.Comparator
                            .comparing(Candidate::contextSupported)
                            .thenComparingDouble(c -> c.match().score()))
                    .orElse(candidates.get(from));
            selected.add(best);
        }
        return selected;
    }

    private String contextText(List<ReviewInput.Segment> segments) {
        if (segments.isEmpty()) return null;
        return segments.stream().map(s -> s.role() == ScreenTextRole.BACKGROUND
                        ? "[배경 화면 글자] " + s.text() : s.role() == ScreenTextRole.UNCERTAIN
                        ? "[출처 불확실 화면 글자] " + s.text() : s.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 발언과 화면 글자를 한 목록으로 합친다. 앞뒤 맥락을 잡기 위해 시간순으로 둔다. */
    private List<Line> collectLines(AnalysisContext context) {
        List<Line> lines = new ArrayList<>();
        if (context.hasTranscript()) {
            for (int i = 0; i < context.transcript().size(); i++) {
                TranscriptSegment s = context.transcript().get(i);
                if (s.getText() == null || s.getText().isBlank()) continue;
                lines.add(new Line(ReviewInput.id(TimelineEventType.SPEECH, s.getId(), i), TimelineEventType.SPEECH,
                        s.getStartMs(), s.getEndMs(), s.getText(), null));
            }
        }
        if (context.hasScreenText()) {
            for (int i = 0; i < context.screenTexts().size(); i++) {
                ScreenText s = context.screenTexts().get(i);
                if (!s.isEditorial()) continue;
                if (s.getText() == null || s.getText().isBlank()) continue;
                lines.add(new Line(ReviewInput.id(TimelineEventType.CAPTION, s.getId(), i), TimelineEventType.CAPTION,
                        s.getStartMs(), s.getEndMs(), s.getText(), s.getFrame()));
            }
        }
        lines.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return lines;
    }

    private record Line(String inputId, TimelineEventType type, long startMs, long endMs,
                        String text, VideoFrame frame) {}

    private record Candidate(int index, Line line, ContextLexicon.Match match,
                             String before, String after, String relatedText) {
        boolean contextSupported() {
            return match.contextSupported() && !match.commonUsageSupported();
        }
    }
}
