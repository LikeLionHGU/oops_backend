package com.example.oops.expression;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** User-provided watch list, not a claim about intent, origin or wrongdoing. Exact raw-text matches only. */
@Component
public class ExpressionDictionary {
    public record Entry(String id, String category, List<String> aliases, String commonUsageNote) {}
    public record Archive(String version, List<Entry> entries) {}
    public record Hit(String expressionId, String category, String matchedText, int startOffset,
                      int endOffset, String dictionaryVersion, String commonUsageNote) {}

    private static final Set<String> CATEGORIES = Set.of("PROFANITY", "DISPUTED_EXPRESSION");
    // No substring/fuzzy matching. A finite particle/ending allowlist preserves raw offsets.
    private static final String BOUNDARY = "[^\\p{L}\\p{N}_]";
    private static final String ENDING = "(?:은|는|이|가|을|를|의|도|만|에|에서|에게|한테|처럼|같이|로|으로|라고|이라|이라는|라|야|여|네|다|하다|했다|하고|하며|하는|하네|합니다|입니다|이다|였다|였어|였어요|래|거든|인가|인가요|아요|어요|어요는)";

    private final Archive archive;
    private final Map<String, Entry> entryByAlias;
    private final Pattern pattern;

    public ExpressionDictionary() {
        try (var input = ExpressionDictionary.class.getResourceAsStream("/expression-dictionary.json")) {
            archive = JsonMapper.builder().build().readValue(input, Archive.class);
            if (archive.version() == null || archive.entries().isEmpty()) throw new IllegalArgumentException("Empty expression dictionary");
            Set<String> ids = new HashSet<>();
            Map<String, Entry> aliases = new HashMap<>();
            for (var e : archive.entries()) {
                if (e.id() == null || e.id().length() > 64 || !ids.add(e.id())
                        || !CATEGORIES.contains(e.category())
                        || e.aliases() == null || e.aliases().isEmpty()
                        || e.commonUsageNote() == null || e.commonUsageNote().length() > 300)
                    throw new IllegalArgumentException("Invalid expression entry");
                for (var alias : e.aliases()) {
                    if (alias == null || alias.isBlank() || alias.length() > 40 || aliases.putIfAbsent(alias, e) != null
                            || alias.equals("~노")) throw new IllegalArgumentException("Invalid expression alias");
                }
            }
            entryByAlias = Map.copyOf(aliases);
            // Longest alias first: at each position the alternation takes the longest valid match,
            // and the scan resumes after it, so hits never overlap.
            String alternatives = entryByAlias.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                    .map(Pattern::quote).collect(Collectors.joining("|"));
            pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives
                    + ")(?=$|" + BOUNDARY + "|" + ENDING + "(?=$|" + BOUNDARY + "))");
        } catch (Exception e) { throw new IllegalStateException("Expression dictionary could not be loaded", e); }
    }

    public String version() { return archive.version(); }

    public List<Hit> detect(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Hit> hits = new ArrayList<>();
        var m = pattern.matcher(raw);
        while (m.find()) {
            var entry = entryByAlias.get(m.group());
            hits.add(new Hit(entry.id(), entry.category(), m.group(), m.start(), m.end(), version(), entry.commonUsageNote()));
        }
        return List.copyOf(hits);
    }
}
