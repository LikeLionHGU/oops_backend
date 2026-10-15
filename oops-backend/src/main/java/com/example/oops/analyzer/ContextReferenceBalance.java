package com.example.oops.analyzer;

import java.util.*;
import java.util.function.Predicate;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.ContextExample;

/** Bounded local heuristic, NOT semantic similarity, classification confidence or a risk score. */
final class ContextReferenceBalance {
    static final String REVISION = "2026-10-15-reference-balance-1";
    // Experimental search weights. Lexical relevance can beat an unrelated routed example.
    private static final double HINT_BONUS = .15;
    private static final double NEW_KEY_BONUS = .10;
    private static final int MAX_OMISSION_TRACES = 20;
    public record Choice(String exampleId, double lexicalComponent, double hintBonus,
                         double newKeyBonus, double selectionScore, List<String> matchedKeys,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                         double relationComponent) {}
    public record Omission(String exampleId, String reason) {}
    public record Trace(String revision, List<String> availableKeys, List<String> selectedKeys,
                        List<String> uncoveredKeys, List<Choice> choices, int sizeOmissions,
                        int countOmissions, List<Omission> omissions, boolean omissionsTruncated) {}
    record Plan(List<ContextExample> selected, Trace trace) {}

    static Plan select(List<ContextExampleSelector.Match> matches, Set<String> hints, int max,
                       boolean diversifyFamilies, Predicate<List<ContextExample>> fits) {
        return select(matches,hints,max,diversifyFamilies,fits,true);
    }
    /** Package-local experimental ablation; every existing runtime call retains the default bonus. */
    static Plan select(List<ContextExampleSelector.Match> matches, Set<String> hints, int max,
                       boolean diversifyFamilies, Predicate<List<ContextExample>> fits, boolean rewardNewKeys) {
        Map<String,ContextExampleSelector.Match> unique = new LinkedHashMap<>();
        for (var m : matches) unique.merge(m.example().id(),m,(a,b) -> a.score() >= b.score() ? a : b);
        var remaining = new ArrayList<>(unique.values());
        Map<String,Integer> order = new HashMap<>();
        for (var m : remaining) order.put(m.example().id(),order.size());
        var available = new TreeSet<String>();
        remaining.forEach(m -> available.addAll(keys(m.example(),hints)));
        Set<String> covered = new TreeSet<>(), families = new HashSet<>();
        List<ContextExample> selected = new ArrayList<>(); List<Choice> choices = new ArrayList<>();
        List<Omission> omissions = new ArrayList<>(); int sizeOmissions=0;
        while (!remaining.isEmpty() && selected.size()<max) {
            var sorted = remaining.stream().sorted(Comparator
                    .comparingDouble((ContextExampleSelector.Match m) -> score(m,hints,covered,rewardNewKeys)).reversed()
                    .thenComparingInt(m -> diversifyFamilies && families.contains(m.example().familyId()) ? 1 : 0)
                    .thenComparingInt(m -> order.get(m.example().id()))).toList();
            boolean added=false;
            for (var m : sorted) {
                remaining.remove(m);
                var proposed = new ArrayList<>(selected); proposed.add(m.example());
                if (!fits.test(proposed)) {
                    sizeOmissions++;
                    if (omissions.size()<MAX_OMISSION_TRACES) omissions.add(new Omission(m.example().id(),"SIZE_LIMIT"));
                    continue;
                }
                var keys = keys(m.example(),hints);
                double lexical=lexical(m.score()), hint=keys.isEmpty()?0:HINT_BONUS,
                        fresh=rewardNewKeys && keys.stream().anyMatch(k -> !covered.contains(k))?NEW_KEY_BONUS:0;
                choices.add(new Choice(m.example().id(),lexical,hint,fresh,lexical+hint+fresh+m.relationComponent(),
                        List.copyOf(keys),m.relationComponent()));
                selected.add(m.example()); covered.addAll(keys); families.add(m.example().familyId()); added=true;
                break;
            }
            if (!added) break;
        }
        int countOmissions=remaining.size();
        remaining.forEach(m -> { if (omissions.size()<MAX_OMISSION_TRACES)
            omissions.add(new Omission(m.example().id(),"COUNT_LIMIT")); });
        var missing = new TreeSet<>(available); missing.removeAll(covered);
        String revision = matches.stream().anyMatch(m -> m.relationComponent()>0)
                ? "2026-10-15-reference-relation-balance-2" : REVISION;
        if (!rewardNewKeys) revision+="-new-key-bonus-off-experiment";
        return new Plan(List.copyOf(selected),new Trace(revision,List.copyOf(available),List.copyOf(covered),List.copyOf(missing),
                List.copyOf(choices),sizeOmissions,countOmissions,List.copyOf(omissions),sizeOmissions+countOmissions>omissions.size()));
    }
    private static SortedSet<String> keys(ContextExample e,Set<String> hints) {
        var keys = new TreeSet<String>();
        if (e.routingKeys()!=null) e.routingKeys().stream().filter(hints::contains).forEach(keys::add);
        return keys;
    }
    private static double lexical(double score) { return score/(1+score); }
    private static double score(ContextExampleSelector.Match m,Set<String> hints,Set<String> covered,boolean rewardNewKeys) {
        var keys = keys(m.example(),hints);
        return lexical(m.score())+m.relationComponent()+(keys.isEmpty()?0:HINT_BONUS)
                +(rewardNewKeys && keys.stream().anyMatch(k -> !covered.contains(k))?NEW_KEY_BONUS:0);
    }
}
