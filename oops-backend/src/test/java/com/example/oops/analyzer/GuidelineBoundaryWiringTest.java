package com.example.oops.analyzer;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Optional private-archive wiring regression. Mock outputs are probes, NOT model judgments. */
class GuidelineBoundaryWiringTest {
    private static final Path ROOT = Path.of("../datasets/controversy");
    private static final String EXAMPLE = "pisik-yeongyang#s3-life-choice";
    private static final String BOUNDARY = "가정 조리·메뉴 유래 설명·가족 선호는 정상 대조다.";
    private ReviewGuidelineLibrary library(Path path, String excluded) {
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                path.toAbsolutePath().toUri().toString(), 6000, 2, 3000, excluded);
        library.load();
        assertThat(library.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        return library;
    }
    @Test void splitBoundaryIsSelectedAndFrozenIntoActualCandidateRequestWithoutProvider() throws Exception {
        // This is a historical v4 boundary ablation, not a test of whichever runtime is active.
        // Promotion to v7 must not silently skip the preserved v4 regression.
        Path active = ROOT.resolve("guidelines/runtime.json");
        Path runtime = Files.exists(active) && JSON.readTree(Files.readString(active)).path("version").asText()
                .equals("2026-10-15-life-choice-boundary-v1-patterns-4") ? active
                : ROOT.resolve("legacy/runtime-history/f08f1dc40890e67871d1194b8acf67bd0e5d97ec382594412a5f37f058de1907.json");
        Path baseline = ROOT.resolve("legacy/runtime-history/aa8e90044981fe403aa4ddce1c04102794ac54b4d828dc7d93a3e20e8aeaccbc.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(runtime) && Files.exists(baseline));
        org.junit.jupiter.api.Assumptions.assumeTrue(JSON.readTree(Files.readString(runtime)).path("version").asText()
                .equals("2026-10-15-life-choice-boundary-v1-patterns-4"));
        var before = library(baseline, ""); var after = library(runtime, "");
        var excluded = library(runtime, "pisik-yeongyang-20240511");
        var cases = TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json")).cases();
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var item : cases) {
            var fixture = new CandidateReviewEngineTest();
            var context = fixture.context(item.speech().toArray(String[]::new));
            int batchIndex = 0;
            for (var batch : TextReviewBatchPlanner.plan(context.reviewInput(), TimelineEventType.SPEECH, 3)) {
                var raw = new ArrayList<>(batch.primary()); raw.addAll(batch.context());
                var old = before.select(TimelineEventType.SPEECH, raw, "before");
                var selected = after.select(TimelineEventType.SPEECH, raw, "after");
                var heldOut = excluded.select(TimelineEventType.SPEECH, raw, "excluded");
                assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
                assertThat(heldOut.trace().exampleIds()).noneMatch(id -> id.startsWith("pisik-yeongyang#"));
                if (item.id().equals("P-B-development-excerpt")) {
                    assertThat(old.trace().exampleIds()).doesNotContain(EXAMPLE);
                    assertThat(selected.trace().exampleIds()).contains(EXAMPLE);
                    assertThat(selected.prompt()).contains(BOUNDARY, "지역 생활");
                }
                if (item.id().equals("P-C-development-excerpt") || item.id().equals("P-D-development-excerpt"))
                    assertThat(selected.trace().exampleIds()).isEqualTo(old.trace().exampleIds());
                if (item.id().equals("N5-owner-origin")) {
                    // Related normal text may retrieve the example; that is not a positive label.
                    assertThat(selected.trace().exampleIds()).contains(EXAMPLE);
                    assertThat(selected.prompt()).contains(BOUNDARY);
                }
                rows.add(Map.of("caseId", item.id(), "batch", ++batchIndex,
                        "before", old.trace(), "after", selected.trace(), "excluded", heldOut.trace()));
            }
            if (!item.id().equals("P-B-development-excerpt")) continue;
            // One forced neutral candidate exercises actual freeze/group/verification wiring.
            // It does not claim that discovery would find this candidate or that B should PASS.
            List<String> systems = new ArrayList<>();
            List<String> discoverySystems = new ArrayList<>();
            when(fixture.client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
                discoverySystems.add((String) invocation.getArgument(0));
                var input = JSON.readTree((String) invocation.getArgument(1));
                List<String> ids = new ArrayList<>(); input.get("primaryIds").forEach(id -> ids.add(id.asText()));
                if (!ids.contains("stt-index-0")) return Optional.of(new Discovery(ids, List.of(), List.of(), false));
                return Optional.of(new Discovery(ids, List.of(new Proposal("stt-index-0", "TARGET_TREATMENT",
                        "배선 점검용 강제 후보이며 의미 판단이 아니다",
                        List.of(new Quote("stt-index-0", item.speech().get(0))))), List.of(), false));
            });
            when(fixture.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
                systems.add((String) invocation.getArgument(0));
                return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                        fixture.assessment("stt-index-0", "PASS", item.speech().get(0))))));
            });
            var result = run(fixture.client, context, 24, null, null, after, null, 0);
            assertThat(systems).hasSize(1).allSatisfy(system -> assertThat(system).contains(BOUNDARY));
            assertThat(systems.get(0).substring(systems.get(0).indexOf("contextReference=")))
                    .isEqualTo(discoverySystems.get(0).substring(discoverySystems.get(0).indexOf("contextReference=")));
            assertThat(result.diagnostics().candidatePipeline().verificationCalls()).isEqualTo(1);
            assertThat(result.diagnostics().candidatePipeline().repairCalls()).isZero();
        }
        System.out.println("BOUNDARY_WIRING_AUDIT=" + JSON.writeValueAsString(Map.of(
                "providerCalls", 0, "semanticAccuracyMeasured", false,
                "mockedVerificationIsNotModelDecision", true, "selections", rows)));
    }

    @Test void promotedV7SelectsSeparateDevelopmentUnitsWithoutRouterOrModel() throws Exception {
        Path active=ROOT.resolve("guidelines/runtime.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(active));
        String text=Files.readString(active);
        org.junit.jupiter.api.Assumptions.assumeTrue(JSON.readTree(text).path("schemaVersion").asText().equals("review-guidelines-7"));
        var promoted=ContextRankReadinessAudit.library(text);
        var expected=Map.of("P-B-development-excerpt","pisik-yeongyang#u-life-choice",
                "P-C-development-excerpt","pisik-yeongyang#u-menu","P-D-development-excerpt","pisik-yeongyang#u-graphic");
        for(var item:TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json")).cases()) {
            if(!expected.containsKey(item.id())) continue;
            var context=new CandidateReviewEngineTest().context(item.speech().toArray(String[]::new));
            var selected=promoted.select(TimelineEventType.SPEECH,context.reviewInput().segments(),item.id());
            assertThat(selected.trace().exampleIds()).contains(expected.get(item.id()));
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            assertThat(selected.prompt()).contains("합성 개발 대조");
        }
    }
}
