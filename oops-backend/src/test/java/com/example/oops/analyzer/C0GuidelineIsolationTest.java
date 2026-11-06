package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * C0 = the production base criteria with zero reference cases. Uses the private local runtime
 * archive; skipped explicitly when it is absent. Not an accuracy check.
 */
class C0GuidelineIsolationTest {
    private static final Path RUNTIME = Path.of("../datasets/controversy/guidelines/runtime.json");
    private static final int PROBES = 8;

    /** Probe with the archive's own retrieval views so production is expected to attach cases. */
    private static List<String> probes(String runtime) {
        List<String> texts = new ArrayList<>();
        for (var example : ReviewGuidelineLibrary.JSON.readTree(runtime).path("examples")) {
            boolean speech = false;
            for (var channel : example.path("channels")) speech |= channel.asText().equals("SPEECH");
            String text = example.path("retrievalView").path("text").asText();
            if (speech && !text.isBlank()) texts.add(text);
            if (texts.size() == PROBES) break;
        }
        return texts;
    }

    private static JsonNode reference(ReviewGuidelineLibrary library, String text) {
        var segment = new ReviewInput.Segment("stt-index-0", TimelineEventType.SPEECH, 0, 1500, text, null);
        var prompt = library.select(TimelineEventType.SPEECH, List.of(segment), "probe", List.of(segment), Set.of(),
                ContextRelationRetrieval.Query.empty()).prompt();
        String marker = "contextReference=";
        int start = prompt.indexOf(marker);
        assertThat(start).isNotNegative();
        return ReviewGuidelineLibrary.JSON.readTree(prompt.substring(start + marker.length()));
    }

    @Test void baseOnlyKeepsTheProductionCriteriaAndSelectsNoCases() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUNTIME), "Private runtime archive not present");
        String runtime = Files.readString(RUNTIME);
        var c0 = ConstraintBaselineTrial.baseOnly(runtime);
        var production = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime), true, "classpath:frozen.json",
                6000, 3, 3000, "", true, true);
        production.load();
        int productionCases = 0;
        var probes = probes(runtime);
        assertThat(probes).isNotEmpty();
        for (String probe : probes) {
            var base = reference(c0, probe);
            var prod = reference(production, probe);
            assertThat(base.path("referenceContexts").isArray()).isTrue();
            assertThat(base.path("referenceContexts")).isEmpty();
            assertThat(base.path("reviewGuidelines")).isEqualTo(prod.path("reviewGuidelines"));
            assertThat(base.path("reviewGuidelines")).hasSize(6);
            productionCases += prod.path("referenceContexts").size();
        }
        // The comparison is meaningful only if production would have added cases for some probe.
        assertThat(productionCases).isPositive();
    }
}
