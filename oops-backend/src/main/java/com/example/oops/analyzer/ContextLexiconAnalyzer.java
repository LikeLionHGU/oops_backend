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
 *     ↓ 사전 매칭 (일반 용법 신호가 있으면 여기서 버림)
 *   걸린 것들
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

    private final ContextLexicon lexicon;
    private final ContextValidator validator;

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
        List<Line> lines = collectLines(context);
        List<Candidate> candidates = new ArrayList<>();

        // 1단계 — 사전 매칭. 일반 용법 신호가 있으면 여기서 걸러진다
        for (int i = 0; i < lines.size() && candidates.size() < MAX_MATCHES; i++) {
            Line line = lines.get(i);
            for (ContextLexicon.Match match : lexicon.match(line.text())) {
                // 맥락을 안 봐도 되는 항목은 CommunitySlangRules 가 맡는다.
                // 그쪽은 AI 키가 없어도 돌기 때문에 안전망 역할을 한다.
                if (!match.entry().requiresContextCheck()) {
                    continue;
                }
                candidates.add(new Candidate(candidates.size(), line, match,
                        textAt(lines, i - 1), textAt(lines, i + 1)));
                if (candidates.size() >= MAX_MATCHES) break;
            }
        }

        if (candidates.isEmpty()) {
            log.info("[lexicon] videoId={} 걸린 표현 없음", context.video().getId());
            return List.of();
        }

        // 2단계 — 앞뒤 맥락을 봐야 하는 것만 AI 에게 묻는다
        List<ContextValidator.Request> toValidate = candidates.stream()
                .filter(c -> c.match().entry().requiresContextCheck())
                .map(c -> new ContextValidator.Request(
                        c.index(), c.match().matchedText(),
                        c.match().entry().reason(),
                        c.before(), c.line().text(), c.after()))
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

        log.info("[lexicon] videoId={} 매칭 {}건 → 확인요청 {}건 → 후보 {}건 (제외 {}건)",
                context.video().getId(), candidates.size(), toValidate.size(),
                findings.size(), dropped);
        return findings;
    }

    private RiskFinding build(AnalysisContext context, Candidate c,
                              ContextValidator.Verdict verdict) {
        var entry = c.match().entry();

        // 애매하다고 답한 건은 더 낮춘다. 확신 없는 걸 위로 올리면 안 된다.
        double score = c.match().score();
        if (verdict != null && verdict.isAmbiguous()) {
            score = Math.max(0.2, score - 0.15);
        }

        StringBuilder reason = new StringBuilder(entry.reason());
        if (verdict != null && verdict.note() != null && !verdict.note().isBlank()) {
            reason.append(' ').append(verdict.note());
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
                .reason(reason.toString())
                .target(target)
                .frame(line.frame());

        if (line.type() == TimelineEventType.SPEECH) {
            builder.text(line.text());
        } else {
            builder.captionText(line.text());
        }
        return builder.build();
    }

    private String textAt(List<Line> lines, int index) {
        return index < 0 || index >= lines.size() ? null : lines.get(index).text();
    }

    /** 발언과 화면 글자를 한 목록으로 합친다. 앞뒤 맥락을 잡기 위해 시간순으로 둔다. */
    private List<Line> collectLines(AnalysisContext context) {
        List<Line> lines = new ArrayList<>();
        if (context.hasTranscript()) {
            for (TranscriptSegment s : context.transcript()) {
                lines.add(new Line(TimelineEventType.SPEECH,
                        s.getStartMs(), s.getEndMs(), s.getText(), null));
            }
        }
        if (context.hasScreenText()) {
            for (ScreenText s : context.screenTexts()) {
                lines.add(new Line(TimelineEventType.CAPTION,
                        s.getStartMs(), s.getEndMs(), s.getText(), s.getFrame()));
            }
        }
        lines.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return lines;
    }

    private record Line(TimelineEventType type, long startMs, long endMs,
                        String text, VideoFrame frame) {}

    private record Candidate(int index, Line line, ContextLexicon.Match match,
                             String before, String after) {}
}
