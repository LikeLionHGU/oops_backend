package com.example.oops.analyzer;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Computes, offline, the context the current production reading page (A0-T) gives each label
 * target on the frozen transcript. The label exporter derives Near from this file: every gold
 * context segment provided by A0 means Near. Targets that are not a planned primary are NOT_PLANNED.
 */
public final class A0ProvidedContext {
    static final String FORMAT = "a0-provided-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Entry(String status, List<String> contextSegmentIds) {}

    record Output(String format, String selectorId, String labelVersion, String manifestSha256,
                  Map<String, String> transcriptSha256, Map<String, Map<String, Entry>> provided) {}

    private A0ProvidedContext() {
    }

    static Output compute(Path manifestPath) throws Exception {
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        var manifest = JSON.readTree(manifestBytes);
        Path base = manifestPath.toAbsolutePath().getParent();
        var selector = new DialoguePageContextSelector();
        Map<String, String> hashes = new TreeMap<>();
        Map<String, Map<String, Entry>> provided = new TreeMap<>();
        for (var video : manifest.path("videos")) {
            long videoId = video.path("videoId").asLong();
            Path transcriptPath = base.resolve(video.path("transcriptPath").asText()).toRealPath();
            hashes.put(Long.toString(videoId), ReferenceOnlyTrial.sha(Files.readAllBytes(transcriptPath)));
            var transcript = CandidateReviewEngine.speechInput(ContextExperimentTrial.context(videoId, transcriptPath));
            Set<String> planned = new HashSet<>();
            DialogueReadingPage.plan(transcript).forEach(p -> p.primary().forEach(s -> planned.add(s.id())));
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (var targetNode : video.path("targets")) {
                String targetId = targetNode.asText();
                var target = transcript.find(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown target in video " + videoId + ": " + targetId));
                ContextExperimentTrial.check(!entries.containsKey(targetId), "Duplicate target: " + targetId);
                entries.put(targetId, planned.contains(targetId)
                        ? new Entry("PROVIDED", selector.select(target, transcript).contextSegmentIds())
                        : new Entry("NOT_PLANNED", List.of()));
            }
            ContextExperimentTrial.check(provided.put(Long.toString(videoId), entries) == null, "Duplicate video: " + videoId);
        }
        return new Output(FORMAT, DialoguePageContextSelector.ID, manifest.path("labelVersion").asText(),
                ReferenceOnlyTrial.sha(manifestBytes), hashes, provided);
    }

    static void write(Path manifestPath, Path out) throws Exception {
        Files.write(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(compute(manifestPath)), StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args) throws Exception {
        ContextExperimentTrial.check(args.length == 2, "Usage: A0ProvidedContext MANIFEST NEW_OUTPUT");
        write(Path.of(args[0]), Path.of(args[1]));
        System.out.println("A0_PROVIDED_WRITTEN providerCalls=0 sha=" + ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(args[1]))));
    }
}
