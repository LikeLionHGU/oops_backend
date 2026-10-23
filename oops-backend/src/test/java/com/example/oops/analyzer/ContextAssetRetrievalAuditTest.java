package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual selector only, not candidate discovery or model judgment. No network calls. */
class ContextAssetRetrievalAuditTest {
    @Test void auditFrozenDevelopmentQueriesAgainstActiveAndContextOnlyPreview() throws Exception {
        Path root = Path.of("../datasets/controversy");
        String configured = System.getenv("CONTEXT_ASSET_PREVIEW_DIR");
        Path preview = configured == null ? root.resolve("experiments/speech-act-mechanism-20261023-verified")
                : Path.of(configured);
        assertThat(preview.toAbsolutePath().normalize()).startsWith(root.resolve("experiments").toAbsolutePath().normalize());
        assumeTrue(Files.exists(preview.resolve("preview-runtime.json")));
        var json = ReviewGuidelineLibrary.JSON;
        var queries = json.readTree(Files.readAllBytes(preview.resolve("queries.json")));
        List<Map<String, Object>> results = new ArrayList<>();
        for (var arm : List.of("active", "context-preview")) {
            var file = arm.equals("active") ? root.resolve("guidelines/runtime.json") : preview.resolve("preview-runtime.json");
            var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                    file.toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
            library.load();
            assertThat(library.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
            for (var query : queries) {
                var raw = new ArrayList<ReviewInput.Segment>();
                for (var row : query.get("raw")) raw.add(new ReviewInput.Segment(row.get("id").asText(),
                        TimelineEventType.SPEECH, row.path("startMs").asLong(), row.path("endMs").asLong(),
                        row.get("text").asText(), null));
                var selected = library.select(TimelineEventType.SPEECH, raw, query.get("caseId").asText());
                assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
                assertThat(selected.trace().exampleIds()).hasSizeLessThanOrEqualTo(3);
                assertThat(selected.prompt()).doesNotContain("expectedDecision", "userAnswer", "labelScope");
                var result = new LinkedHashMap<String, Object>();
                result.put("arm", arm);
                result.put("caseId", query.get("caseId").asText());
                result.put("trace", selected.trace());
                result.put("selfReferenceRetrieved", selected.trace().exampleIds().contains(query.get("caseId").asText()));
                result.put("mechanismPacketRetrieved", selected.trace().exampleIds().contains("speech-act-dev#reported-vs-enacted"));
                result.put("promptUtf8Bytes", selected.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                results.add(result);
            }
        }
        assertThat(results).hasSize(12);
        var active = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                root.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString(), 6000, 3, 3000, "", true);
        active.load();
        var archive = json.readTree(Files.readAllBytes(root.resolve("guidelines/runtime.json")));
        List<Map<String, Object>> probes = new ArrayList<>();
        for (var example : archive.get("examples")) {
            var channels = new ArrayList<String>();
            example.get("channels").forEach(c -> channels.add(c.asText()));
            var channel = channels.contains("SPEECH") ? TimelineEventType.SPEECH : TimelineEventType.CAPTION;
            var selected = active.select(channel, List.of(new ReviewInput.Segment("index-probe", channel,
                    0, 1000, example.at("/retrievalView/text").asText(), 1.0,
                    channel == TimelineEventType.CAPTION ? com.example.oops.domain.ScreenTextRole.EDITORIAL : null)), "index-probe");
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            probes.add(Map.of("id", example.get("id").asText(), "selected", selected.trace().exampleIds(),
                    "selfIndexProbeRetrieved", selected.trace().exampleIds().contains(example.get("id").asText()),
                    "payloadCodePoints", selected.trace().payloadCodePoints()));
        }
        assertThat(probes).hasSize(43);
        String output = System.getenv("CONTEXT_ASSET_AUDIT_OUTPUT");
        if (output != null && !output.isBlank()) {
            Path destination = Path.of(output).toAbsolutePath();
            assertThat(destination.getParent().toRealPath()).startsWith(root.resolve("experiments").toRealPath());
            var report = Map.of("schemaVersion", "context-asset-selector-audit-1", "providerCalls", 0,
                    "independentAccuracyMeasured", false, "manualRoutingHints", false,
                    "sameSourceDevelopmentReplay", true, "results", results,
                    "indexProbesNotRealVideoQueries", probes);
            Files.writeString(destination, json.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        }
    }
}
