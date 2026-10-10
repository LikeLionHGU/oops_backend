package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Local lexical routing only. Similarity is NOT a risk score or semantic proof. */
final class ContextExampleSelector {
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+");
    private record Indexed(ReviewGuidelineLibrary.ContextExample example, Set<String> terms) {}
    record Match(ReviewGuidelineLibrary.ContextExample example, double score) {}
    private final List<Indexed> index;
    private final Map<String, Integer> frequencies = new HashMap<>();

    ContextExampleSelector(List<ReviewGuidelineLibrary.ContextExample> examples,
                           List<ReviewGuidelineLibrary.Rule> rules) {
        Map<String, String> conditions = new HashMap<>();
        rules.forEach(r -> conditions.put(r.id(), r.condition()));
        index = examples.stream().map(e -> {
            String text = String.join(" ", e.context().flow()) + " "
                    + String.join(" ", e.context().criticismHypotheses()) + " "
                    + e.mechanismIds().stream().map(conditions::get).reduce("", (a, b) -> a + " " + b);
            var terms = terms(text);
            terms.forEach(t -> frequencies.merge(t, 1, Integer::sum));
            return new Indexed(e, terms);
        }).toList();
    }
    List<Match> rank(TimelineEventType channel, Collection<ReviewInput.Segment> raw, Set<String> excludedFamilies) {
        // Source IDs, hypotheses, URLs, popularity and article metadata are not queries.
        StringBuilder query = new StringBuilder();
        for (var s : raw) {
            if (s.type() == channel && s.reviewTarget() && s.text() != null) {
                int remaining = 24000 - query.length();
                if (remaining <= 0) break;
                query.append(s.text(), 0, Math.min(s.text().length(), remaining)).append(' ');
            }
            if (query.length() >= 24000) break;
        }
        Set<String> queryTerms = terms(query.toString());
        List<Match> matches = new ArrayList<>();
        for (var item : index) {
            var e = item.example();
            if (!e.channels().contains(channel.name()) || excludedFamilies.contains(e.familyId())) continue;
            int hits = 0; double score = 0;
            for (var t : item.terms()) if (queryTerms.contains(t)) {
                hits++; score += 1 + Math.log((index.size() + 1.0) / (frequencies.get(t) + 1.0));
            }
            if (hits >= 3) matches.add(new Match(e, score / Math.sqrt(Math.max(1, item.terms().size()))));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(m -> m.example().id()));
        return matches;
    }
    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        var matcher = WORDS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        int[] previous = null;
        while (matcher.find()) {
            int[] word = matcher.group().codePoints().toArray();
            // Character fragments provide limited Korean inflection tolerance, not semantic understanding.
            for (int i = 0; i + 3 <= word.length; i++) result.add(new String(word, i, 3));
            // Also retain short multiword expressions; Korean words may each be only two characters.
            if (previous != null) {
                int tail = Math.min(2, previous.length), head = Math.min(2, word.length);
                int[] boundary = new int[tail + head];
                System.arraycopy(previous, previous.length - tail, boundary, 0, tail);
                System.arraycopy(word, 0, boundary, tail, head);
                for (int i = 0; i + 3 <= boundary.length; i++) result.add("b:" + new String(boundary, i, 3));
            }
            previous = word;
        }
        return result;
    }
}
