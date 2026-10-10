package com.example.oops.expression;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;

/** User-provided watch list, not a claim about intent, origin or wrongdoing. Exact raw-text matches only. */
@Component
public class ExpressionDictionary {
    public record Entry(String id, String category, List<String> aliases, String commonUsageNote) {}
    public record Archive(String version, List<Entry> entries) {}
    public record Hit(String expressionId, String category, String matchedText, int startOffset,
                      int endOffset, String dictionaryVersion, String commonUsageNote) {}
    private record MatcherEntry(Entry entry, Pattern pattern) {}
    private final Archive archive;
    private final List<MatcherEntry> matchers;
    public ExpressionDictionary() {
        try (var input = ExpressionDictionary.class.getResourceAsStream("/expression-dictionary.json")) {
            archive = JsonMapper.builder().build().readValue(input, Archive.class);
            if (archive.version() == null || archive.entries().isEmpty()) throw new IllegalArgumentException("Empty expression dictionary");
            Set<String> ids = new HashSet<>(), aliases = new HashSet<>();
            List<MatcherEntry> compiled = new ArrayList<>();
            for (var e : archive.entries()) {
                if (e.id() == null || e.id().length() > 64 || !ids.add(e.id())
                        || !Set.of("PROFANITY", "DISPUTED_EXPRESSION").contains(e.category())
                        || e.aliases() == null || e.aliases().isEmpty()
                        || e.commonUsageNote() == null || e.commonUsageNote().length() > 300)
                    throw new IllegalArgumentException("Invalid expression entry");
                for (var alias : e.aliases()) {
                    if (alias == null || alias.isBlank() || alias.length() > 40 || !aliases.add(alias)
                            || alias.equals("~노")) throw new IllegalArgumentException("Invalid expression alias");
                }
                String alternatives = e.aliases().stream().sorted(Comparator.comparingInt(String::length).reversed())
                        .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
                // No substring/fuzzy matching. A finite particle/ending allowlist preserves raw offsets.
                String boundary = "[^\\p{L}\\p{N}_]";
                String ending = "(?:은|는|이|가|을|를|의|도|만|에|에서|에게|한테|처럼|같이|로|으로|라고|이라|이라는|라|야|여|네|다|하다|했다|하고|하며|하는|하네|합니다|입니다|이다|였다|였어|였어요|래|거든|인가|인가요|아요|어요|어요는)";
                compiled.add(new MatcherEntry(e, Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives
                        + ")(?=$|" + boundary + "|" + ending + "(?=$|" + boundary + "))")));
            }
            matchers = List.copyOf(compiled);
        } catch (Exception e) { throw new IllegalStateException("Expression dictionary could not be loaded", e); }
    }
    public String version() { return archive.version(); }
    public List<Hit> detect(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Hit> found = new ArrayList<>();
        for (var entry : matchers) {
            var m = entry.pattern().matcher(raw);
            while (m.find()) found.add(new Hit(entry.entry().id(), entry.entry().category(), m.group(),
                    m.start(), m.end(), version(), entry.entry().commonUsageNote()));
        }
        found.sort(Comparator.comparingInt(Hit::startOffset)
                .thenComparing(Comparator.comparingInt(Hit::endOffset).reversed()).thenComparing(Hit::expressionId));
        List<Hit> selected=new ArrayList<>();
        int previousEnd=-1;
        for (var h : found) if (h.startOffset() >= previousEnd) {
            selected.add(h); previousEnd=h.endOffset();
        }
        return List.copyOf(selected);
    }
}
