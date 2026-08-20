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

    /** AI 문구가 사전 문구와 이만큼 겹치면 같은 말로 보고 안 붙인다 */
    private static final double SAME_MEANING = 0.75;

    /**
     * 되풀이로 볼 재활용 비율.
     *
     * AI 문구 중 사전 문구에서 그대로 가져온 글자가 이 비율을 넘으면
     * 새 정보가 아니라 같은 말을 다시 한 것으로 본다.
     *
     * 실측으로 정했다.
     *   되풀이 문장    38%  → 버림
     *   새 정보 문장   23%, 0%  → 붙임
     */
    private static final double RECYCLED = 0.35;

    /** 이만큼 연속으로 겹쳐야 "가져다 썼다" 로 센다. 짧으면 우연히 겹친다 */
    private static final int RUN = 6;

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
        int menuSkipped = 0;
        for (int i = 0; i < lines.size() && candidates.size() < MAX_MATCHES; i++) {
            Line line = lines.get(i);

            // 메뉴판·가격표는 편집 자막이 아니라 가게가 만든 글자다.
            // 식당 메뉴판의 '낙지전골' 이 사전의 '낙지'(정치 맥락)에 걸려
            // '정치인' 카드가 메뉴판 사진과 함께 나간 적이 있다.
            // 앞뒤가 온통 음식 이름이라 AI 도 판단 근거가 없다.
            // 앞뒤 맥락으로는 쓰되(textAt) 여기서 뽑지는 않는다.
            if (line.type() == TimelineEventType.CAPTION
                    && ScreenTextShape.looksLikePriceList(line.text())) {
                menuSkipped++;
                continue;
            }

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

        if (menuSkipped > 0) {
            log.info("[lexicon] videoId={} 가격표로 보이는 화면 글자 {}건은 건너뜀",
                    context.video().getId(), menuSkipped);
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

        // 사전 문구와 AI 문구가 같은 말이면 하나만 남긴다.
        // 둘 다 붙이면 "커뮤니티 말투로 읽히기도 합니다. 커뮤니티 말투로 읽힐 수 있습니다."
        // 처럼 같은 문장이 두 번 나온다. 읽는 사람이 신뢰를 잃는다.
        String reason = entry.reason();
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
     * 실제로 이런 카드가 나왔다.
     *
     *   "기계 소리를 나타내는 일상 표현이지만, 특정 커뮤니티 용법을 두고
     *    논쟁이 있었던 표현입니다. 특정 커뮤니티에서 논쟁이 있었던 표현으로,
     *    일반적인 의미 외에 다른 맥락으로 읽힐 수 있다."
     *
     * 같은 말을 두 번 한다. 읽는 사람은 도구가 대충 만든다고 느낀다.
     *
     * 글자를 하나씩 세는 방식으로는 이걸 못 잡는다.
     * 뒤 문장에 "일반적인 의미 외에" 같은 새 글자가 섞여 비율이 내려간다.
     * 그래서 **사전 문구를 얼마나 통째로 가져다 썼는지**를 함께 본다.
     */
    private boolean addsSomething(String reason, String note) {
        String a = normalize(reason);
        String b = normalize(note);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }

        // 사전 문구를 많이 가져다 썼으면 되풀이다
        if (recycledRatio(a, b) >= RECYCLED) {
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

    /**
     * note 중에서 reason 을 그대로 가져다 쓴 글자가 몇 퍼센트인지.
     *
     * 가장 긴 겹침만 보면 안 된다. 되풀이 문장과 새 정보 문장이
     * 똑같이 8글자씩 겹쳐서 구분이 안 됐다.
     * **얼마나 많이 재활용했는지**를 재야 갈린다.
     */
    private double recycledRatio(String a, String b) {
        boolean[] covered = new boolean[b.length()];

        for (int i = 0; i < b.length(); i++) {
            for (int j = b.length(); j >= i + RUN; j--) {
                if (a.contains(b.substring(i, j))) {
                    for (int k = i; k < j; k++) {
                        covered[k] = true;
                    }
                    break;
                }
            }
        }

        int count = 0;
        for (boolean c : covered) {
            if (c) count++;
        }
        return b.isEmpty() ? 0 : (double) count / b.length();
    }

    /** 비교용으로 공백과 기호를 뺀다 */
    private String normalize(String text) {
        return text.replaceAll("[^가-힣a-zA-Z0-9]", "");
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
