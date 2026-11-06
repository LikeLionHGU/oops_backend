package com.example.oops.analyzer;

import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.domain.TranscriptSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    /** Every contextReference payload sent in one experimental run, discovery and verification alike. */
    private static List<String> sentReferences(ReviewGuidelineLibrary guidelines, List<String> contextIds) {
        var f = new CandidateReviewEngineTest();
        var lines = List.of("그건 사실이 아니었어", "다른 이야기", "아무 말", "그 집은 정말 별로다", "다음 주제");
        long[] starts = {0, 2_000, 4_000, 480_000, 482_000};
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) segments.add(new TranscriptSegment(f.video, starts[i], starts[i] + 1500, lines.get(i)));
        var context = new AnalysisContext(f.video, ContentGenre.GENERAL, segments, List.of());
        f.discovery(List.of("stt-index-3"), new Proposal("stt-index-3", "TARGET_TREATMENT",
                "가게에 대한 평가가 이용자나 사장을 낮추는 쪽으로 이어지는지 원문으로 확인합니다.",
                List.of(new Quote("stt-index-3", "그 집"))));
        f.verification(new Verification("candidate-1", f.assessment("stt-index-3", "PASS", "그 집")));
        runExperimental(f.client, context, new RunServices(null, null, guidelines, null, null),
                new RunOptions(24, 8, false, new VerificationFeatures(false, false, false), false),
                new ExperimentalBatch(List.of("stt-index-3"), contextIds));
        var systems = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client, atLeastOnce()).completeAsJson(systems.capture(), anyString(), any());
        List<String> references = new ArrayList<>();
        for (String system : systems.getAllValues()) {
            int start = system.indexOf("contextReference=");
            if (start < 0) continue;
            int end = system.indexOf('\n', start);
            references.add(system.substring(start, end < 0 ? system.length() : end));
        }
        return references;
    }

    @Test void c0ReferencesAreTheSameFixedCriteriaInDiscoveryAndVerificationWhateverContextIsSelected() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUNTIME), "Private runtime archive not present");
        var c0 = ConstraintBaselineTrial.baseOnly(Files.readString(RUNTIME));
        var near = sentReferences(c0, List.of("stt-index-2"));
        var far = sentReferences(c0, List.of("stt-index-0", "stt-index-1", "stt-index-4"));
        assertThat(near).hasSizeGreaterThanOrEqualTo(2);
        assertThat(far).hasSizeGreaterThanOrEqualTo(2);
        assertThat(near).containsOnly(near.get(0));
        assertThat(far).containsOnly(near.get(0));
        var payload = ReviewGuidelineLibrary.JSON.readTree(near.get(0).substring("contextReference=".length()));
        assertThat(payload.path("referenceContexts")).isEmpty();
        assertThat(payload.path("reviewGuidelines")).hasSize(6);
    }
}
