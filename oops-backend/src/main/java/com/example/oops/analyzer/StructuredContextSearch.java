package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Authored local concept expansion for retrieval only; neither semantic inference nor a verdict. */
final class StructuredContextSearch {
    record Definition(String id, List<String> mechanismIds, List<String> queryAll, List<String> referenceAll) {}
    record Catalog(String revision, List<Definition> profiles) {}
    private record Profile(Definition definition, List<Pattern> query, List<Pattern> reference) {}
    record Trace(String revision, String catalogSha256, List<String> queryProfileIds, Map<String,List<String>> matchedExamples,
                 List<String> selectedExampleIds) {}
    private final String revision;
    private final String catalogSha256;
    private final List<Profile> profiles;

    private StructuredContextSearch(Catalog catalog, String catalogSha256) {
        if (catalog.revision() == null || catalog.profiles() == null || catalog.profiles().size() > 32)
            throw new IllegalArgumentException("Bounded search catalog required");
        revision = catalog.revision();
        this.catalogSha256 = catalogSha256;
        Set<String> ids = new HashSet<>();
        profiles = catalog.profiles().stream().map(d -> {
            if (d.id() == null || !d.id().matches("[a-z][a-z0-9-]{0,63}") || !ids.add(d.id())
                    || d.mechanismIds() == null || d.mechanismIds().isEmpty())
                throw new IllegalArgumentException("Unique search profiles and mechanisms required");
            return new Profile(d, compile(d.queryAll()), compile(d.referenceAll()));
        }).toList();
    }
    static StructuredContextSearch load() {
        try (var in = StructuredContextSearch.class.getResourceAsStream("/context-search-profiles.json")) {
            if (in == null) throw new IllegalStateException("Search catalog missing");
            byte[] bytes = in.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalArgumentException("Search catalog too large");
            return new StructuredContextSearch(ReviewGuidelineLibrary.JSON.readValue(bytes, Catalog.class),
                    ReviewGuidelineLibrary.retrievalHash(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load search catalog", ex); }
    }
    private static List<Pattern> compile(List<String> expressions) {
        if (expressions == null || expressions.isEmpty() || expressions.size() > 8
                || expressions.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 350))
            throw new IllegalArgumentException("Bounded authored expressions required");
        return expressions.stream().map(Pattern::compile).toList();
    }
    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static boolean matches(List<Pattern> patterns, String text) {
        return patterns.stream().allMatch(p -> p.matcher(text).find());
    }
    Set<String> queryProfiles(TimelineEventType channel, Collection<ReviewInput.Segment> raw) {
        var lines = raw.stream().filter(s -> s.type() == channel && s.reviewTarget() && s.text() != null)
                .sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id)).toList();
        Set<String> found = new TreeSet<>();
        // Do not combine separate conversations at opposite ends of a review batch.
        for (int i = 0; i < lines.size(); i++) {
            StringBuilder window = new StringBuilder();
            for (int j = i; j < lines.size(); j++) {
                var s = lines.get(j);
                if (j > i && s.endMs() - lines.get(i).startMs() > 20000) break;
                if (window.length() + s.text().length() > 24000) break;
                window.append(s.text()).append(' ');
            }
            String text = normalized(window.toString());
            profiles.stream().filter(p -> matches(p.query(), text)).forEach(p -> found.add(p.definition().id()));
        }
        return Collections.unmodifiableSet(found);
    }
    List<String> match(ReviewGuidelineLibrary.ContextExample example, Set<String> queryProfiles) {
        // Frozen search text only: mutable criticism/normal explanations never enter the index.
        if (example.retrievalView() == null) return List.of();
        String text = normalized(example.retrievalView().text());
        return profiles.stream().filter(p -> queryProfiles.contains(p.definition().id())
                && p.definition().mechanismIds().stream().anyMatch(example.mechanismIds()::contains)
                && matches(p.reference(), text)).map(p -> p.definition().id()).sorted().toList();
    }
    Trace trace(Set<String> queryProfiles, List<ContextExampleSelector.Match> ranked,
                List<ReviewGuidelineLibrary.ContextExample> selected) {
        Map<String,List<String>> matches = new TreeMap<>();
        ranked.stream().filter(m -> !m.searchProfiles().isEmpty())
                .forEach(m -> matches.put(m.example().id(), m.searchProfiles()));
        return new Trace(revision, catalogSha256, List.copyOf(queryProfiles), Collections.unmodifiableMap(matches),
                selected.stream().map(ReviewGuidelineLibrary.ContextExample::id).filter(matches::containsKey).toList());
    }
}
