package com.example.oops.analyzer;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.ContextExample;

/** Bounded reference-boundary coverage. Retrieval hints only, never semantic proof or verdicts. */
class ContextBoundarySupplement {
    protected ContextBoundarySupplement() {}
    enum Cue { DEFINITION, REPORTED, REQUEST }
    record Plan(List<ContextExample> selected, Set<Cue> queryCues, String state,
                String addedId, String removedId) {}

    public record Trace(String revision, String state, Set<Cue> queryCues, String addedId, String removedId) {}
    static Trace trace(Plan plan) {
        return new Trace("2026-10-23-context-boundary-supplement-1",plan.state(),plan.queryCues(),plan.addedId(),plan.removedId());
    }

    static Set<Cue> cues(String text) {
        Set<Cue> out = EnumSet.noneOf(Cue.class);
        if (has("뜻|의미|줄임말|줄인\\s*말|뭐예요|뭔가요|정의", text)) out.add(Cue.DEFINITION);
        if (has("인용|회고|피해\\s*경험|과거|사례|멘트", text)
                && has("피하|취지|충격|각성|들[은었]|전달|회고|인용", text)) out.add(Cue.REPORTED);
        if (has("보여\\s*주|알려\\s*주|알고\\s*싶|공개.{0,8}요청|답변.{0,8}요청|직접\\s*요청", text)) out.add(Cue.REQUEST);
        return Collections.unmodifiableSet(out);
    }
    private static boolean has(String expression, String text) {
        return Pattern.compile(expression).matcher(text).find();
    }
    static Set<Cue> queryCues(Collection<ReviewInput.Segment> raw) {
        var lines = raw.stream().filter(s -> s.reviewTarget() && s.type() == com.example.oops.domain.TimelineEventType.SPEECH)
                .sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id)).toList();
        Set<Cue> found = EnumSet.noneOf(Cue.class);
        for (int i = 0; i < lines.size(); i++) {
            var window = new StringBuilder();
            for (int j = i; j < lines.size(); j++) {
                if (lines.get(j).endMs() - lines.get(i).startMs() > 20000 || window.length()+lines.get(j).text().length()>4000) break;
                window.append(lines.get(j).text()).append(' ');
            }
            found.addAll(cues(window.toString()));
        }
        return Collections.unmodifiableSet(found);
    }
    private static boolean qualified(ContextExample example, Set<Cue> query) {
        if (example.retrievalView() == null) return false;
        var reference = cues(example.retrievalView().text());
        boolean contrast = reference.contains(Cue.REQUEST)
                && (reference.contains(Cue.DEFINITION) || reference.contains(Cue.REPORTED));
        return contrast && !Collections.disjoint(reference, query);
    }
    static Plan select(List<ContextExampleSelector.Match> eligiblePool, List<ContextExample> baseline,
                       Collection<ReviewInput.Segment> raw, int max, Predicate<List<ContextExample>> fits) {
        return select(eligiblePool,baseline,raw,List.of(),max,fits);
    }
    static Plan select(List<ContextExampleSelector.Match> eligiblePool, List<ContextExample> baseline,
                       Collection<ReviewInput.Segment> raw, Collection<ReviewInput.Segment> focus,
                       int max, Predicate<List<ContextExample>> fits) {
        if (max < 0 || baseline.size()>max) throw new IllegalArgumentException("Bounded selection required");
        // Resolve focus against raw IDs: caller-provided text is not a new evidence source.
        var focusIds = new HashSet<String>();
        focus.forEach(s -> focusIds.add(s.id()));
        var anchors = raw.stream().filter(s -> focusIds.contains(s.id()) && s.reviewTarget()
                && s.type()==com.example.oops.domain.TimelineEventType.SPEECH).toList();
        if (!focus.isEmpty() && anchors.isEmpty())
            return new Plan(List.copyOf(baseline),Set.of(),"FOCUS_NOT_IN_RAW",null,null);
        var near = focus.isEmpty() ? raw : raw.stream().filter(s -> anchors.stream().anyMatch(f ->
                s.startMs()>=f.startMs()-15000 && s.startMs()<=f.endMs()+15000)).toList();
        var query = queryCues(near);
        if (query.isEmpty()) return new Plan(List.copyOf(baseline),query,"NO_DISCOURSE_CUE",null,null);
        if (baseline.stream().anyMatch(e -> qualified(e,query)))
            return new Plan(List.copyOf(baseline),query,"BOUNDARY_ALREADY_COVERED",null,null);
        if (max == 0) return new Plan(List.copyOf(baseline),query,"DISABLED",null,null);
        for (var match : eligiblePool) {
            var example = match.example();
            // Do not turn generic words like "meaning" into a new topical match.
            if (match.searchProfiles().isEmpty() && !match.routed()) continue;
            if (!qualified(example,query) || baseline.contains(example)) continue;
            var proposed = new ArrayList<>(baseline);
            String removed = null;
            // Retain leading references; at most one trailing slot can change.
            if (proposed.size()==max && proposed.size()==1) continue;
            if (proposed.size()==max) removed = proposed.remove(proposed.size()-1).id();
            proposed.add(example);
            if (fits.test(proposed)) return new Plan(List.copyOf(proposed),query,"BOUNDARY_SUPPLEMENTED",example.id(),removed);
        }
        return new Plan(List.copyOf(baseline),query,"NO_FITTING_BOUNDARY_IN_POOL",null,null);
    }
}
