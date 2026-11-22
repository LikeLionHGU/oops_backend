package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.ToIntFunction;

/**
 * R2-A structural context conditions (docs/experiments/retrieval-rules/r2a-structural-v1.json), offline.
 * Pages are never re-planned: every condition starts from the A0 window and only adds segments.
 */
public final class R2aStructuralContext {
    static final String RULES_SHA256 = "425f8b9236a7e179702d2272bb06d81b3e356653b227b8f2e4612ad07238a3a8";
    static final String FORMAT = "r2a-structural-conditions-v1";
    static final int LIMIT = DialogueReadingPage.MAX_INPUT_CODE_POINTS - 256;
    static final long SUFFIX_MS = 10_000, SUFFIX_GAP_MS = 5_000;
    static final int SUFFIX_CODE_POINTS = 1_000, NEIGHBORS = 5, EDGE_PRIMARIES = 3, EXPANSION = 10;
    static final List<String> CONDITIONS = List.of("S0_A0", "S1_PAGE_OVERLAP", "S2_NEIGHBOR_GUARANTEE", "S3_BOUNDARY_EXPANSION");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Expansion(List<String> theoretical, List<String> withinLimit, List<String> trimmed, int addedSegments,
                     int addedCodePoints, int inputCodePoints, String status) {}

    record Fit(List<ReviewInput.Segment> kept, List<ReviewInput.Segment> removed, int size, String status) {}

    private R2aStructuralContext() {
    }

    static Map<String, Expansion> expand(ReviewInput input, String targetId) {
        List<ReviewInput.Segment> source = speech(input);
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < source.size(); i++) order.put(source.get(i).id(), i);
        ReviewInput.Segment target = input.find(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown target: " + targetId));
        var page = pageOf(input, targetId);
        List<String> s0Ids = new DialoguePageContextSelector().select(target, input).contextSegmentIds();
        Set<String> base = new HashSet<>(s0Ids);
        base.add(targetId);
        List<ReviewInput.Segment> baseWindow = source.stream().filter(s -> base.contains(s.id())).toList();
        int last = order.get(page.primary().get(page.primary().size() - 1).id());
        int firstWindow = order.get(DialogueReadingPage.raw(page).get(0).id());
        int t = order.get(targetId);
        int position = page.primary().indexOf(target);

        Map<String, List<ReviewInput.Segment>> candidates = new LinkedHashMap<>();
        candidates.put("S0_A0", List.of());
        candidates.put("S1_PAGE_OVERLAP", suffix(source, last));
        candidates.put("S2_NEIGHBOR_GUARANTEE", range(source, t - NEIGHBORS, t + NEIGHBORS + 1));
        List<ReviewInput.Segment> s3 = new ArrayList<>();
        if (position < EDGE_PRIMARIES) s3.addAll(range(source, firstWindow - EXPANSION, firstWindow));
        if (position >= page.primary().size() - EDGE_PRIMARIES) s3.addAll(range(source, last + 1, last + 1 + EXPANSION));
        candidates.put("S3_BOUNDARY_EXPANSION", s3);

        ToIntFunction<ReviewInput.Segment> index = s -> order.get(s.id());
        ToIntFunction<List<ReviewInput.Segment>> size = w -> DialogueReadingPage.inputCodePoints(w, page.primary());
        Map<String, Expansion> result = new LinkedHashMap<>();
        candidates.forEach((condition, extra) -> {
            List<ReviewInput.Segment> added = extra.stream().filter(s -> !base.contains(s.id())).distinct()
                    .sorted(Comparator.comparingInt(index)).toList();
            var fit = fit(baseWindow, added, target, index, size, LIMIT);
            result.put(condition, new Expansion(ids(merge(baseWindow, added, index), targetId),
                    ids(merge(baseWindow, fit.kept(), index), targetId), fit.removed().stream().map(ReviewInput.Segment::id).toList(),
                    added.size(), added.stream().mapToInt(R2aStructuralContext::codePoints).sum(), fit.size(), fit.status()));
        });
        return result;
    }

    static Fit fit(List<ReviewInput.Segment> base, List<ReviewInput.Segment> added, ReviewInput.Segment target,
                   ToIntFunction<ReviewInput.Segment> order, ToIntFunction<List<ReviewInput.Segment>> size, int limit) {
        int baseSize = size.applyAsInt(merge(base, List.of(), order));
        if (baseSize > limit) return new Fit(List.of(), List.of(), baseSize, "S0_OVER_LIMIT");
        int t = order.applyAsInt(target);
        List<ReviewInput.Segment> kept = new ArrayList<>(added);
        List<ReviewInput.Segment> removed = new ArrayList<>();
        Comparator<ReviewInput.Segment> farthestFirst = Comparator
                .comparingInt((ReviewInput.Segment s) -> Math.abs(order.applyAsInt(s) - t)).reversed()
                .thenComparing(Comparator.comparingInt(order).reversed());
        int current = size.applyAsInt(merge(base, kept, order));
        while (current > limit) {
            var drop = kept.stream().min(farthestFirst).orElseThrow();
            kept.remove(drop);
            removed.add(drop);
            current = size.applyAsInt(merge(base, kept, order));
        }
        return new Fit(List.copyOf(kept), List.copyOf(removed), current, removed.isEmpty() ? "OK" : "TRIMMED");
    }

    static Map<String, String> strata(ReviewInput input) {
        Map<String, String> strata = new LinkedHashMap<>();
        for (var page : DialogueReadingPage.plan(input)) {
            var primary = page.primary();
            for (int i = 0; i < primary.size(); i++)
                strata.put(primary.get(i).id(), i < EDGE_PRIMARIES || i >= primary.size() - EDGE_PRIMARIES ? "EDGE" : "INTERIOR");
        }
        return strata;
    }

    private static List<ReviewInput.Segment> speech(ReviewInput input) {
        return input.segments().stream().filter(s -> s.type() == TimelineEventType.SPEECH && s.reviewTarget()).toList();
    }

    private static TextReviewBatchPlanner.Batch pageOf(ReviewInput input, String targetId) {
        return DialogueReadingPage.plan(input).stream()
                .filter(p -> p.primary().stream().anyMatch(s -> s.id().equals(targetId)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Target is not a planned primary: " + targetId));
    }

    private static List<ReviewInput.Segment> suffix(List<ReviewInput.Segment> source, int last) {
        List<ReviewInput.Segment> out = new ArrayList<>();
        int chars = 0;
        for (int i = last + 1; i < source.size(); i++) {
            var s = source.get(i);
            if (s.startMs() - source.get(i - 1).endMs() > SUFFIX_GAP_MS || s.endMs() - source.get(last).endMs() > SUFFIX_MS
                    || chars + codePoints(s) > SUFFIX_CODE_POINTS) break;
            chars += codePoints(s);
            out.add(s);
        }
        return out;
    }

    private static List<ReviewInput.Segment> range(List<ReviewInput.Segment> source, int from, int toExclusive) {
        return source.subList(Math.max(0, from), Math.min(source.size(), Math.max(0, toExclusive)));
    }

    private static List<ReviewInput.Segment> merge(List<ReviewInput.Segment> base, List<ReviewInput.Segment> added,
                                                   ToIntFunction<ReviewInput.Segment> order) {
        List<ReviewInput.Segment> all = new ArrayList<>(base);
        all.addAll(added);
        all.sort(Comparator.comparingInt(order));
        return all;
    }

    private static List<String> ids(List<ReviewInput.Segment> window, String targetId) {
        return window.stream().map(ReviewInput.Segment::id).filter(id -> !id.equals(targetId)).toList();
    }

    private static int codePoints(ReviewInput.Segment s) {
        return s.text().codePointCount(0, s.text().length());
    }

    static Map<String, Object> compute(Path manifestPath) throws Exception {
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        var manifest = JSON.readTree(manifestBytes);
        Path base = manifestPath.toAbsolutePath().getParent();
        Map<String, String> hashes = new TreeMap<>();
        Map<String, Object> videos = new TreeMap<>();
        for (var video : manifest.path("videos")) {
            long videoId = video.path("videoId").asLong();
            Path transcriptPath = base.resolve(video.path("transcriptPath").asText()).toRealPath();
            hashes.put(Long.toString(videoId), ReferenceOnlyTrial.sha(Files.readAllBytes(transcriptPath)));
            var input = CandidateReviewEngine.speechInput(ContextExperimentTrial.context(videoId, transcriptPath));
            var strata = strata(input);
            Map<String, Object> targets = new LinkedHashMap<>();
            for (var node : video.path("targets")) {
                String id = node.asText();
                ContextExperimentTrial.check(!targets.containsKey(id), "Duplicate target: " + id);
                targets.put(id, strata.containsKey(id)
                        ? Map.of("status", "PLANNED", "stratum", strata.get(id), "conditions", expand(input, id))
                        : Map.of("status", "NOT_PLANNED"));
            }
            videos.put(Long.toString(videoId), targets);
        }
        return Map.of("format", FORMAT, "rulesSha256", RULES_SHA256, "pageRevision", DialogueReadingPage.REVISION,
                "limitCodePoints", LIMIT, "labelVersion", manifest.path("labelVersion").asText(),
                "manifestSha256", ReferenceOnlyTrial.sha(manifestBytes), "transcriptSha256", hashes, "videos", videos);
    }

    static Map<String, Object> strataFile(Path transcriptPath, long videoId) throws Exception {
        var input = CandidateReviewEngine.speechInput(ContextExperimentTrial.context(videoId, transcriptPath));
        return Map.of("format", "r2a-page-strata-v1", "rulesSha256", RULES_SHA256, "pageRevision", DialogueReadingPage.REVISION,
                "transcriptSha256", ReferenceOnlyTrial.sha(Files.readAllBytes(transcriptPath)), "strata", strata(input));
    }

    public static void main(String[] args) throws Exception {
        ContextExperimentTrial.check(args.length >= 3, "Usage: conditions MANIFEST NEW_OUTPUT | strata TRANSCRIPT VIDEO_ID NEW_OUTPUT");
        Object output;
        Path out;
        if (args[0].equals("conditions")) {
            output = compute(Path.of(args[1]));
            out = Path.of(args[2]);
        } else if (args[0].equals("strata") && args.length == 4) {
            output = strataFile(Path.of(args[1]), Long.parseLong(args[2]));
            out = Path.of(args[3]);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
        Files.write(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(output), StandardOpenOption.CREATE_NEW);
        System.out.println("R2A_WRITTEN providerCalls=0 sha=" + ReferenceOnlyTrial.sha(Files.readAllBytes(out)));
    }
}
