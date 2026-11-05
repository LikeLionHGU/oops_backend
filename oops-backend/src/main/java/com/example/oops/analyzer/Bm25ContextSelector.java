package com.example.oops.analyzer;

import java.text.Normalizer;
import java.util.*;

/**
 * A5-T: Okapi BM25 (k1=1.2, b=0.75) with the target text as query over every transcript segment.
 * Hangul/CJK words become character bigrams so particles do not block matches; other words stay whole.
 * Zero-score segments are never selected. Greedy by rank under budget B; a ranked segment that
 * does not fit is skipped, not truncated.
 */
final class Bm25ContextSelector implements ContextSelector {
    private static final String POLICY = "bm25-bigram-v1";
    private static final double K1 = 1.2, B = 0.75;

    record Scored(String segmentId, double score) {}

    private final ContextTokenBudget budget;

    Bm25ContextSelector(ContextTokenBudget budget) {
        this.budget = Objects.requireNonNull(budget);
    }

    String id() {
        return "A5-T:" + POLICY + ":k1=" + K1 + ":b=" + B + ":" + ContextTokenBudget.COUNTER_ID + ":B=" + budget.budget();
    }

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var rendering = new ExperimentalBatchRendering(transcript, Set.of(target.id()));
        List<ReviewInput.Segment> chosen = new ArrayList<>();
        for (var scored : ranking(target, transcript)) {
            var candidate = transcript.find(scored.segmentId()).orElseThrow();
            List<ReviewInput.Segment> raw = new ArrayList<>(chosen);
            raw.add(target);
            raw.add(candidate);
            if (!budget.measure(rendering, raw).exceeded()) chosen.add(candidate);
        }
        var order = transcript.segments();
        var ids = chosen.stream().sorted(Comparator.comparingInt(order::indexOf)).map(ReviewInput.Segment::id).toList();
        return new ContextSelection(ids, id());
    }

    /** Positive-score segments, best first; ties go to the segment closer in time, then transcript order. */
    List<Scored> ranking(ReviewInput.Segment target, ReviewInput transcript) {
        var order = transcript.segments();
        if (!order.contains(target)) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        Map<String, List<String>> docs = new LinkedHashMap<>();
        order.forEach(s -> docs.put(s.id(), tokens(s.text())));
        double avgLength = docs.values().stream().mapToInt(List::size).average().orElse(0);
        Map<String, Integer> documentFrequency = new HashMap<>();
        docs.values().forEach(d -> new HashSet<>(d).forEach(t -> documentFrequency.merge(t, 1, Integer::sum)));
        var query = new LinkedHashSet<>(docs.get(target.id()));
        int n = docs.size();
        List<Scored> scored = new ArrayList<>();
        for (var s : order) {
            if (s.id().equals(target.id())) continue;
            double score = score(query, docs.get(s.id()), avgLength, documentFrequency, n);
            if (score > 0) scored.add(new Scored(s.id(), score));
        }
        Map<String, ReviewInput.Segment> byId = new HashMap<>();
        order.forEach(s -> byId.put(s.id(), s));
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingLong(s -> distance(target, byId.get(s.segmentId())))
                .thenComparingInt(s -> order.indexOf(byId.get(s.segmentId()))));
        return List.copyOf(scored);
    }

    private static double score(Set<String> query, List<String> doc, double avgLength,
                                Map<String, Integer> documentFrequency, int n) {
        if (doc.isEmpty()) return 0;
        Map<String, Integer> tf = new HashMap<>();
        doc.forEach(t -> tf.merge(t, 1, Integer::sum));
        double norm = K1 * (1 - B + B * doc.size() / avgLength);
        double total = 0;
        for (String term : query) {
            int f = tf.getOrDefault(term, 0);
            if (f == 0) continue;
            int df = documentFrequency.get(term);
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            total += idf * f * (K1 + 1) / (f + norm);
        }
        return total;
    }

    static List<String> tokens(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String word : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            if (!word.codePoints().anyMatch(Bm25ContextSelector::cjk)) {
                tokens.add(word);
                continue;
            }
            int[] cps = word.codePoints().toArray();
            if (cps.length == 1) tokens.add(word);
            for (int i = 0; i + 1 < cps.length; i++) tokens.add(new String(cps, i, 2));
        }
        return tokens;
    }

    private static boolean cjk(int codePoint) {
        var script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }

    private static long distance(ReviewInput.Segment target, ReviewInput.Segment other) {
        return other.startMs() >= target.endMs() ? other.startMs() - target.endMs()
                : Math.max(0, target.startMs() - other.endMs());
    }
}
