package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Test-only information boundary and reading provenance. Not a completed judgment pipeline. */
public final class SourceFirstReviewBoundary {
    static final String VERSION = "source-first-review-boundary-1";
    record Snapshot(String sourceInputSha256, String readingSha256, String producerResultsSha256, String readingJson) {}
    record ReadingUse(String readingSha256, String function, String contentUse, String challengeReason, List<String> challengeEvidenceIds) {}
    enum State { CONSISTENT_DECLARATION, SOURCE_RECHECK_REQUIRED, INCOMPLETE_BINDING }
    record Binding(State state, String detail, boolean publicationAuthorized) {}
    private SourceFirstReviewBoundary() {}

    static String hash(String value) { return ReviewGuidelineLibrary.retrievalHash(value); }
    static JsonNode readingInput(JsonNode envelope) {
        String anchor = envelope.path("anchorId").asText();
        var input = JSON.createObjectNode().put("focusId", anchor);
        var segments = input.putArray("segments"); var seen = new HashSet<String>();
        check(envelope.path("raw").isArray() && !envelope.path("raw").isEmpty(), "Raw source required");
        for (JsonNode line : envelope.path("raw")) {
            String id = line.path("id").asText(), text = line.path("text").asText();
            long start = line.path("startMs").asLong(-1), end = line.path("endMs").asLong(-1);
            check(!id.isBlank() && !text.isBlank() && line.path("startMs").isIntegralNumber()
                    && line.path("endMs").isIntegralNumber() && start >= 0 && end >= start && seen.add(id), "Invalid source row");
            var segment = segments.addObject().put("id", id);
            segment.set("startMs", line.path("startMs").deepCopy());
            segment.set("endMs", line.path("endMs").deepCopy()); segment.put("text", text);
        }
        check(seen.contains(anchor), "Anchor outside raw");
        return input;
    }
    static Snapshot freeze(JsonNode envelope, SourceRelationExtractionTrial.Observation reading, String producerSha) {
        JsonNode source = readingInput(envelope);
        check(producerSha != null && producerSha.matches("[a-f0-9]{64}"), "Producer provenance required");
        String failure = SourceRelationExtractionTrial.sourceFailure(source.toString(), reading);
        check(failure == null, "Invalid source reading: " + failure);
        String sourceSha = hash(source.toString()), readingJson = JSON.writeValueAsString(reading);
        return new Snapshot(sourceSha, hash(sourceSha + producerSha + readingJson), producerSha, readingJson);
    }
    static void verify(JsonNode envelope, Snapshot snapshot) {
        check(snapshot != null && snapshot.producerResultsSha256() != null
                && snapshot.producerResultsSha256().matches("[a-f0-9]{64}"), "Snapshot provenance required");
        String source = readingInput(envelope).toString();
        check(hash(source).equals(snapshot.sourceInputSha256())
                && hash(snapshot.sourceInputSha256() + snapshot.producerResultsSha256() + snapshot.readingJson()).equals(snapshot.readingSha256()), "Source/reading hash mismatch");
        var reading = JSON.readValue(snapshot.readingJson(), SourceRelationExtractionTrial.Observation.class);
        check(SourceRelationExtractionTrial.sourceFailure(source, reading) == null, "Invalid frozen reading");
    }
    static JsonNode judgmentSource(JsonNode envelope, Snapshot snapshot) {
        verify(envelope, snapshot);
        var source = readingInput(envelope);
        var out = JSON.createObjectNode().put("anchorId", source.path("focusId").asText());
        out.set("raw", source.path("segments").deepCopy());
        var binding = out.putObject("sourceReading").put("readingSha256", snapshot.readingSha256())
                .put("sourceInputSha256", snapshot.sourceInputSha256()).put("modelReportedNotHumanGold", true);
        binding.set("observation", JSON.readTree(snapshot.readingJson()));
        return out;
    }
    static Binding bind(JsonNode envelope, Snapshot snapshot, ReadingUse use) {
        try { verify(envelope, snapshot); }
        catch (RuntimeException ex) { return new Binding(State.INCOMPLETE_BINDING, "SOURCE_OR_READING_CHANGED", false); }
        if (use == null || !snapshot.readingSha256().equals(use.readingSha256()))
            return new Binding(State.INCOMPLETE_BINDING, "READING_BINDING_REQUIRED", false);
        var original = JSON.readValue(snapshot.readingJson(), SourceRelationExtractionTrial.Observation.class);
        if (use.function() == null || use.contentUse() == null || use.challengeEvidenceIds() == null)
            return new Binding(State.INCOMPLETE_BINDING, "READING_USE_SHAPE", false);
        var declared = new SourceRelationExtractionTrial.Observation(original.anchorId(), use.function(), use.contentUse(), original.links(), original.evidenceIds());
        if (SourceRelationExtractionTrial.sourceFailure(readingInput(envelope).toString(), declared) != null)
            return new Binding(State.INCOMPLETE_BINDING, "READING_USE_ENUM", false);
        boolean same = original.function().equals(use.function()) && original.contentUse().equals(use.contentUse());
        boolean challenge = use.challengeReason() != null && !use.challengeReason().isBlank();
        if (same && !challenge && use.challengeEvidenceIds().isEmpty()) {
            if ("UNCLEAR".equals(original.function()) || "UNCLEAR".equals(original.contentUse()))
                return new Binding(State.SOURCE_RECHECK_REQUIRED, "SOURCE_READING_UNCLEAR", false);
            return new Binding(State.CONSISTENT_DECLARATION, null, false);
        }
        Set<String> ids = new HashSet<>(); readingInput(envelope).path("segments").forEach(s -> ids.add(s.path("id").asText()));
        if (!challenge || use.challengeReason().length() > 300 || use.challengeEvidenceIds().isEmpty()
                || new HashSet<>(use.challengeEvidenceIds()).size() != use.challengeEvidenceIds().size()
                || use.challengeEvidenceIds().stream().anyMatch(id -> id == null || !ids.contains(id)))
            return new Binding(State.INCOMPLETE_BINDING, "EXPLICIT_SOURCE_CHALLENGE_REQUIRED", false);
        // A challenge is recorded, never silently accepted as a replacement source reading.
        return new Binding(State.SOURCE_RECHECK_REQUIRED, "EXPLICIT_SOURCE_CHALLENGE", false);
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "frozen extraction results and new private directory required");
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        check(RelationGuidedVerificationTrial.EXTRACTION_SHA.equals(ReferenceOnlyTrial.sha(bytes)), "Exact original extraction results required");
        var saved = JSON.readTree(bytes); check("COMPLETE".equals(saved.path("state").asText()), "Completed extraction required");
        var bundle = JSON.createObjectNode().put("version", VERSION).put("providerCalls", 0)
                .put("runtimeConnected", false).put("judgmentContractImplemented", false).put("independentGold", false);
        var rows = bundle.putArray("stages"); var seen = new HashSet<String>();
        for (JsonNode row : saved.path("results")) if (row.path("repeat").asInt() == 1) {
            String id = row.path("conditionId").asText(); check(RelationGuidedVerificationTrial.IDS.contains(id) && seen.add(id), "Known unique first readings required");
            check(JSON.readTree(row.path("providerContent").asText()).equals(row.path("output")), "Original extraction content mismatch");
            var envelope = JSON.createObjectNode().put("anchorId", row.at("/input/focusId").asText());
            envelope.set("raw", row.at("/input/segments").deepCopy());
            var observation = JSON.treeToValue(row.at("/output/observations/0"), SourceRelationExtractionTrial.Observation.class);
            Snapshot snapshot = freeze(envelope, observation, RelationGuidedVerificationTrial.EXTRACTION_SHA);
            var stage = rows.addObject().put("caseId", id).put("readingRepeatUsed", 1).put("judgmentState", "NOT_RUN");
            stage.put("readerSystem", SourceRelationExtractionTrial.SYSTEM);
            stage.set("readerResponseFormat", JSON.readTree(SourceRelationExtractionTrial.OUTPUT_FORMAT));
            stage.set("readerInput", readingInput(envelope)); stage.set("frozenReading", JSON.valueToTree(snapshot));
            stage.set("freshJudgmentSource", judgmentSource(envelope, snapshot));
        }
        check(seen.size() == 5, "Five source stages required");
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(Path.of("../datasets/controversy/experiments").toRealPath()), "New private output required");
        byte[] output = JSON.writeValueAsBytes(bundle);
        Files.createDirectory(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceRelationExtractionTrial.writePrivate(out.resolve("stage-bundle.json"), output);
        System.out.println("SOURCE_FIRST_BOUNDARY providerCalls=0 savedStages=5 newJudgments=0 bundleSha256=" + ReferenceOnlyTrial.sha(output));
    }
}
