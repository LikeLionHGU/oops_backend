package com.example.oops.analyzer;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Local, curated contrast cases. No scraping, embeddings, external retrieval or model training. */
@Component
public class ReviewCaseLibrary {
    static final int MAX_BYTES = 1_048_576;
    static final JsonMapper JSON = JsonMapper.builder().build();
    private final ResourceLoader resources;
    private final boolean enabled;
    private final String location;
    private final int maxCases, maxCodePoints;
    private final Set<String> excludedFamilies;
    private List<Case> cases = List.of();
    private String state = "NOT_LOADED";
    private String version;

    public ReviewCaseLibrary(ResourceLoader resources,
            @Value("${oops.analysis.review-cases-enabled:true}") boolean enabled,
            @Value("${oops.analysis.review-cases-location:classpath:review-cases.json}") String location,
            @Value("${oops.analysis.review-cases-max-cases:2}") int maxCases,
            @Value("${oops.analysis.review-cases-max-code-points:2400}") int maxCodePoints,
            @Value("${oops.analysis.review-cases-excluded-families:}") String excludedFamilies) {
        if (maxCases < 1 || maxCases > 3 || maxCodePoints < 256 || maxCodePoints > 6000)
            throw new IllegalArgumentException("Review case limits must be 1..3 cases and 256..6000 code points");
        if (location == null || !(location.startsWith("classpath:") || location.startsWith("file:")))
            throw new IllegalArgumentException("Review cases require a local file or classpath resource");
        if (location.startsWith("file:")) {
            try {
                var uri = java.net.URI.create(location);
                if (uri.getAuthority() != null && !uri.getAuthority().isEmpty() || !java.nio.file.Path.of(uri).isAbsolute())
                    throw new IllegalArgumentException("Nonlocal case archive");
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Review cases require an absolute local file URI"); }
        }
        this.resources = resources; this.enabled = enabled; this.location = location;
        this.maxCases = maxCases; this.maxCodePoints = maxCodePoints;
        this.excludedFamilies = excludedFamilies == null ? Set.of() : Arrays.stream(excludedFamilies.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Archive(String version, List<Case> cases) {}
    public record Case(String caseId, String familyId, String sourceFingerprint, String sourceKind,
            String split, String reviewStatus, Boolean rightsCleared, String rightsBasis,
            List<String> reviewerIds, String reviewedAt, List<String> retrievalTerms,
            String utterance, String context, String criticClaim, String counterInterpretation,
            String supportedEvidence, String decision, List<String> missingInformation) {}
    /** Model input deliberately excludes source URLs, comment authors and approval metadata. */
    public record Example(String caseId, String utterance, String context, String criticClaim,
            String counterInterpretation, String supportedEvidence, String decision, List<String> missingInformation) {}
    public record Trace(String archiveVersion, String state, int eligibleCases, int matchedCases,
                        int budgetSkipped, int payloadCodePoints, List<String> caseIds) {
        public Trace { caseIds = List.copyOf(caseIds); }
    }
    record Selection(List<Example> examples, Trace trace) {
        Selection { examples = List.copyOf(examples); }
    }

    @PostConstruct
    void load() {
        cases = List.of(); version = null;
        if (!enabled) { state = "DISABLED"; return; }
        try (InputStream in = resources.getResource(location).getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { state = "ARCHIVE_TOO_LARGE"; return; }
            Archive archive = JSON.readValue(bytes, Archive.class);
            if (archive == null || !text(archive.version(), 64) || archive.cases() == null || archive.cases().size() > 1000)
                throw new IllegalArgumentException("Invalid case archive");
            Set<String> ids = new HashSet<>();
            for (Case c : archive.cases()) if (!valid(c) || !ids.add(c.caseId()))
                throw new IllegalArgumentException("Invalid case record");
            version = archive.version(); cases = List.copyOf(archive.cases());
            state = cases.isEmpty() ? "EMPTY" : "READY";
        } catch (Exception ex) {
            // Optional aid failure must not break or silently certify current-video analysis.
            // Do not log archive contents, paths, comments or exception bodies.
            state = "ARCHIVE_UNAVAILABLE_OR_INVALID"; cases = List.of(); version = null;
        }
    }

    private static boolean valid(Case c) {
        if (c == null || !id(c.caseId()) || !id(c.familyId()) || c.sourceFingerprint() == null
                || !c.sourceFingerprint().matches("[0-9a-f]{64}")
                || !Set.of("DIRECT_FEEDBACK", "LICENSED_DATA", "SYNTHETIC").contains(value(c.sourceKind()))
                || !Set.of("TRAIN", "VALIDATION", "TEST").contains(value(c.split()))
                || !Set.of("APPROVED", "DRAFT", "REJECTED").contains(value(c.reviewStatus()))
                || c.rightsCleared() == null || !text(c.rightsBasis(), 300)
                || c.reviewerIds() == null || c.reviewerIds().size() > 6
                || c.reviewerIds().stream().anyMatch(s -> !id(s))
                || new HashSet<>(c.reviewerIds()).size() != c.reviewerIds().size()
                || c.retrievalTerms() == null || c.retrievalTerms().size() < 2 || c.retrievalTerms().size() > 12
                || c.retrievalTerms().stream().anyMatch(s -> !text(s, 40) || normalize(s).codePointCount(0, normalize(s).length()) < 2)
                || new HashSet<>(c.retrievalTerms().stream().map(ReviewCaseLibrary::normalize).toList()).size() != c.retrievalTerms().size()
                || !text(c.utterance(), 500) || !text(c.context(), 800) || !text(c.criticClaim(), 400)
                || !text(c.counterInterpretation(), 400) || !text(c.supportedEvidence(), 500)
                || !Set.of("PASS", "REVIEW_REQUIRED", "UNCERTAIN").contains(value(c.decision()))
                || c.missingInformation() == null || c.missingInformation().size() > 6
                || c.missingInformation().stream().anyMatch(s -> !text(s, 200))) return false;
        if (!c.utterance().contains(c.supportedEvidence()) && !c.context().contains(c.supportedEvidence())) return false;
        if ("UNCERTAIN".equals(c.decision()) != !c.missingInformation().isEmpty()) return false;
        if (!"APPROVED".equals(c.reviewStatus())) return true;
        if (c.reviewerIds().size() < 2 || c.reviewedAt() == null) return false;
        try { return !LocalDate.parse(c.reviewedAt()).isAfter(LocalDate.now()); }
        catch (Exception ex) { return false; }
    }

    Selection select(List<ReviewInput.Segment> raw, String currentFingerprint) {
        return select(raw, currentFingerprint, null);
    }
    Selection select(List<ReviewInput.Segment> raw, String currentFingerprint, String currentFamilyId) {
        if (!"READY".equals(state)) return new Selection(List.of(), new Trace(version, state, 0, 0, 0, 0, List.of()));
        String query = normalize(raw.stream().map(ReviewInput.Segment::text).collect(java.util.stream.Collectors.joining(" ")));
        record Match(Case c, int score) {}
        Set<String> blockedFamilies = new HashSet<>(excludedFamilies);
        if (currentFamilyId != null) blockedFamilies.add(currentFamilyId);
        cases.stream().filter(c -> !"TRAIN".equals(c.split()) || c.sourceFingerprint().equals(currentFingerprint))
                .forEach(c -> blockedFamilies.add(c.familyId()));
        List<Case> eligible = cases.stream().filter(c -> "APPROVED".equals(c.reviewStatus())
                && Boolean.TRUE.equals(c.rightsCleared()) && "TRAIN".equals(c.split())
                && !"SYNTHETIC".equals(c.sourceKind()) && !blockedFamilies.contains(c.familyId())).toList();
        var ranked = eligible.stream().map(c -> new Match(c, (int)c.retrievalTerms().stream()
                        .map(ReviewCaseLibrary::normalize).filter(query::contains).count()))
                .filter(m -> m.score() >= 2)
                .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(m -> m.c().caseId())).toList();
        List<Example> selected = new ArrayList<>(); Set<String> families = new HashSet<>(); int skipped = 0;
        // Prefer a relevant normal contrast, when available, before filling the remaining slots.
        var order = new ArrayList<Match>();
        if (!ranked.isEmpty()) order.add(ranked.get(0));
        if (maxCases > 1 && !ranked.isEmpty() && !"PASS".equals(ranked.get(0).c().decision())) {
            ranked.stream().filter(m -> "PASS".equals(m.c().decision())
                    && !m.c().familyId().equals(ranked.get(0).c().familyId())).findFirst().ifPresent(order::add);
        }
        ranked.forEach(m -> { if (!order.contains(m)) order.add(m); });
        for (var match : order) {
            if (families.contains(match.c().familyId())) continue;
            if (selected.size() >= maxCases) { skipped++; continue; }
            var c = match.c();
            var example = new Example(c.caseId(), c.utterance(), c.context(), c.criticClaim(), c.counterInterpretation(),
                    c.supportedEvidence(), c.decision(), c.missingInformation());
            var trial = new ArrayList<>(selected); trial.add(example);
            if (payloadSize(trial) > maxCodePoints) { skipped++; continue; }
            selected.add(example); families.add(c.familyId());
        }
        return new Selection(selected, new Trace(version, selected.isEmpty() ? ranked.isEmpty() ? "NO_MATCH" : "BUDGET_EXCLUDED" : "SELECTED",
                eligible.size(), ranked.size(), skipped, selected.isEmpty() ? 0 : payloadSize(selected), selected.stream().map(Example::caseId).toList()));
    }
    private static int payloadSize(List<Example> examples) {
        String json = JSON.writeValueAsString(examples);
        return json.codePointCount(0, json.length());
    }
    static String fingerprint(List<ReviewInput.Segment> raw) {
        String text = normalize(raw.stream().map(ReviewInput.Segment::text).collect(java.util.stream.Collectors.joining(" ")));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String value(String s) { return s == null ? "" : s; }
    private static boolean id(String s) { return s != null && s.matches("[a-zA-Z0-9_-]{1,64}"); }
    private static boolean text(String s, int max) { return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= max; }
}
