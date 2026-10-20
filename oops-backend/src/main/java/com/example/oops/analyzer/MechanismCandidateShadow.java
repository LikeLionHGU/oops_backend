package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.util.*;
import java.util.regex.Pattern;

/** Offline, surface-signal candidate audit only. No verdicts, speaker inference or provider calls. */
public final class MechanismCandidateShadow {
    public static final String REVISION = "2026-10-20-surface-candidate-shadow-1";
    public record Signal(String segmentId, String kind, int startOffset, int endOffset, String matchedText) {}
    public record Candidate(String mechanismId, String origin, String ruleRevision, String anchorId,
                            List<Signal> matchedSignals, List<String> contextIds) {}
    public record Result(String revision, List<Candidate> candidates, int omittedCandidates, int overlongSegments) {}
    private record Slot(String kind, Pattern pattern) {}
    private record Rule(String id, String anchorKind, List<Slot> slots) {}
    private static Slot slot(String kind, String regex) { return new Slot(kind, Pattern.compile(regex)); }
    private static final List<Rule> RULES = List.of(
        new Rule("graphic-body-image", "BODY_ACTION_SURFACE", List.of(slot("BODY_ACTION_SURFACE",
            "(?<![가-힣a-zA-Z0-9])(?:살점|피부|신체|몸통|머리|살|팔|다리|목)(?:을|를)?\\s*(?:뜯|찢|도려|절단|자르|잘라|베어)"))),
        new Rule("service-choice-devaluation", "VALUE_NEGATION_SURFACE", List.of(
            slot("VALUE_NEGATION_SURFACE", "(?:의미|가치|쓸모)(?:가|는|도)?\\s*(?:없|없어|없다)"),
            slot("CHOICE_RESTRICTION_SURFACE", "(?:주는\\s*대[로루]|선택(?:권|지)?(?:이|가|은|는)?\\s*없|먹어야\\s*(?:돼|되))"))),
        new Rule("collective-evaluation", "EVALUATION_SURFACE", List.of(
            slot("GROUP_SURFACE", "집단|사람들|남자들|여자들|주민|이용자|유저|직업|세대|진영"),
            slot("SCOPE_SURFACE", "모두|전체|전부|하나같이|다들|싸잡"),
            slot("EVALUATION_SURFACE", "역겹|열등|무능|문제|비하|잘못|가해|짓밟")))
    );

    public static Result inspect(ReviewInput input) {
        var lines = input.segments().stream().filter(s -> s.type() == TimelineEventType.SPEECH).toList();
        if (lines.size() > 10000 || lines.stream().anyMatch(s -> s.text().length() > 24000))
            throw new IllegalArgumentException("Shadow input limit exceeded");
        Map<String, Candidate> found = new LinkedHashMap<>();
        for (int start = 0; start < lines.size(); start++) {
            for (var rule : RULES) {
                Map<String, Signal> signals = new LinkedHashMap<>();
                for (int end = start; end < Math.min(lines.size(), start + 8); end++) {
                    var line = lines.get(end);
                    // These are explicit audit budgets, not proof of a conversational boundary.
                    if (line.endMs() - lines.get(start).startMs() > 20000
                            || end > start && line.startMs() - lines.get(end - 1).endMs() > 3000) break;
                    for (var slot : rule.slots()) {
                        var matcher = slot.pattern().matcher(line.text());
                        if (!signals.containsKey(slot.kind()) && matcher.find())
                            signals.put(slot.kind(), new Signal(line.id(), slot.kind(), matcher.start(), matcher.end(), matcher.group()));
                    }
                    if (signals.size() != rule.slots().size()) continue;
                    var anchor = signals.get(rule.anchorKind()).segmentId();
                    var candidate = new Candidate(rule.id(), "RULE_SHADOW", REVISION, anchor,
                            List.copyOf(signals.values()), lines.subList(start, end + 1).stream().map(ReviewInput.Segment::id).toList());
                    String key = rule.id() + "|" + anchor;
                    var previous = found.get(key);
                    if (previous == null || candidate.contextIds().size() < previous.contextIds().size()) found.put(key, candidate);
                    break;
                }
            }
        }
        var ordered = found.values().stream().sorted(Comparator
                .comparingLong((Candidate c) -> input.find(c.anchorId()).orElseThrow().startMs())
                .thenComparing(Candidate::anchorId).thenComparing(Candidate::mechanismId)).toList();
        return new Result(REVISION, List.copyOf(ordered.subList(0, Math.min(1000, ordered.size()))),
                Math.max(0, ordered.size() - 1000), (int) lines.stream().filter(s -> s.endMs() - s.startMs() > 20000).count());
    }
}
