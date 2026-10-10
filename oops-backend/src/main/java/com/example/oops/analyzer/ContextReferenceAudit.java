package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline routing audit on saved STT; never calls a provider, starts Spring, or prints text. */
public final class ContextReferenceAudit {
    private ContextReferenceAudit() {}
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Two local JSON paths required");
        try {
            var json = ReviewGuidelineLibrary.JSON;
            var root = json.readTree(read(args[0]));
            var archive = json.readValue(read(args[1]), ReviewGuidelineLibrary.Archive.class);
            var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                    Path.of(args[1]).toAbsolutePath().toUri().toString(), 6000);
            library.load();
            if (!"READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state())
                    || !"review-guidelines-4".equals(archive.schemaVersion())) throw new IllegalArgumentException();
            List<ReviewInput.Segment> raw = new ArrayList<>(); int index = 0;
            for (var row : root.get("transcript")) raw.add(new ReviewInput.Segment("audit-" + index++, TimelineEventType.SPEECH,
                    row.get("startMs").asLong(), row.get("endMs").asLong(), row.get("text").asText(), null));
            if (raw.isEmpty() || raw.size() > 1000) throw new IllegalArgumentException();
            List<ReviewGuidelineLibrary.SelectionTrace> traces = new ArrayList<>();
            for (var batch : TextReviewBatchPlanner.plan(new ReviewInput(raw), TimelineEventType.SPEECH, 3)) {
                var window = new ArrayList<>(batch.primary()); window.addAll(batch.context());
                traces.add(library.select(TimelineEventType.SPEECH, window, "discovery-" + (traces.size() + 1)).trace());
            }
            // Optional fixed raw-only queries. Their presence does not imply model discovery,
            // source approval, or a reconstruction of a previous hidden proposal.
            var queries = root.get("candidateQueries");
            if (queries != null) {
                if (!queries.isArray() || queries.size() > 24) throw new IllegalArgumentException();
                for (var query : queries) {
                    var start = query.get("startMs"); var end = query.get("endMs");
                    var focusTimes = query.get("focusStartMs");
                    if (start == null || end == null || !start.isIntegralNumber() || !end.isIntegralNumber()
                            || start.asLong() < 0 || end.asLong() <= start.asLong() || end.asLong() - start.asLong() > 60000
                            || focusTimes == null || !focusTimes.isArray() || focusTimes.isEmpty() || focusTimes.size() > 8)
                        throw new IllegalArgumentException();
                    var window = raw.stream().filter(s -> s.startMs() >= start.asLong() && s.endMs() <= end.asLong()).toList();
                    if (window.isEmpty() || window.size() > 48) throw new IllegalArgumentException();
                    List<ReviewInput.Segment> focus = new ArrayList<>();
                    Set<Long> times = new HashSet<>();
                    for (var time : focusTimes) {
                        if (!time.isIntegralNumber() || !times.add(time.asLong())) throw new IllegalArgumentException();
                        var matches = window.stream().filter(s -> s.startMs() == time.asLong()).toList();
                        if (matches.size() != 1) throw new IllegalArgumentException();
                        focus.add(matches.get(0));
                    }
                    traces.add(library.select(TimelineEventType.SPEECH, window, "fixed-candidate-" + times.size()
                            + "-" + start.asLong() + "-" + end.asLong(), focus).trace());
                }
            }
            var previousStyle = archive.guidelines().stream().filter(r -> r.channels().contains("SPEECH"))
                    .map(r -> new ReviewGuidelineLibrary.Example(r.id(), r.axis(), r.condition(), r.normalContrast(),
                            r.requiredEvidence(), r.missingContext(), archive.examples().stream()
                            .filter(e -> r.sourceCaseIds().contains(e.id())).map(ReviewGuidelineLibrary.ContextExample::context).toList())).toList();
            String oldPayload = json.writeValueAsString(previousStyle);
            System.out.println(json.writeValueAsString(Map.of("pipelineRevision", CandidateReviewEngine.REVISION,
                    "guidelineReference", library.trace(TimelineEventType.SPEECH), "selections", traces,
                    "previousFullReferenceCodePoints", oldPayload.codePointCount(0, oldPayload.length()),
                    "meanSelectedPayloadCodePoints", traces.stream().mapToInt(ReviewGuidelineLibrary.SelectionTrace::payloadCodePoints).average().orElse(0),
                    "providerCalls", 0, "modelAccuracyMeasured", false)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("INVALID_LOCAL_CONTEXT_AUDIT_" + ex.getClass().getSimpleName());
        }
    }
    private static byte[] read(String path) throws Exception {
        try (var in = Files.newInputStream(Path.of(path))) {
            byte[] bytes = in.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException();
            return bytes;
        }
    }
}
