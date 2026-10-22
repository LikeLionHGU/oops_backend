package com.example.oops.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.TranscriptSegment;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Optional private-data wiring test; no provider calls or model accuracy claims. */
class LocalDevelopmentBoundaryRetrievalTest {
    private static final Path ROOT = Path.of("../datasets/controversy");
    private static final Path RESULTS = ROOT.resolve("experiments/quotation-ocr-followup-20261022-cZy7vL");

    @Test void storedCandidateWindowRetrievesBoundaryAndReconstructedCandidateCarriesItToVerification() throws Exception {
        assumeTrue(Files.exists(RESULTS.resolve("diagnostics-99.json"))
                && Files.exists(ROOT.resolve("context-units/mimiminu-audience-devaluation.json")));
        var json = ReviewGuidelineLibrary.JSON;
        var diagnostics = json.readTree(Files.readAllBytes(RESULTS.resolve("diagnostics-99.json")));
        var candidates = diagnostics.at("/data/snapshot/analyzers/0/candidatePipeline/candidates");
        var stored = java.util.stream.StreamSupport.stream(candidates.spliterator(), false)
                .filter(n -> "stt-index-50".equals(n.path("anchorId").asText())).findFirst().orElseThrow();
        assertThat(stored.path("state").asText()).isEqualTo("PASS");
        var allowed = new java.util.HashSet<String>();
        stored.get("contextSegmentIds").forEach(n -> allowed.add(n.asText()));
        var transcript = json.readTree(Files.readAllBytes(RESULTS.resolve("transcript-99.json"))).get("data");
        var fixture = new CandidateReviewEngineTest();
        var lines = new ArrayList<TranscriptSegment>();
        var window = new ArrayList<ReviewInput.Segment>();
        int index = 0;
        for (var row : transcript) {
            String id = "stt-index-" + index++;
            lines.add(new TranscriptSegment(fixture.video, row.get("startMs").asLong(),
                    row.get("endMs").asLong(), row.get("text").asText()));
            if (allowed.contains(id)) window.add(new ReviewInput.Segment(id, SPEECH,
                    row.get("startMs").asLong(), row.get("endMs").asLong(), row.get("text").asText(), null));
        }
        assertThat(window).hasSize(allowed.size());
        var focus = window.stream().filter(s -> s.id().equals("stt-index-50")).toList();
        assertThat(focus).hasSize(1);
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                ROOT.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString(), 6000);
        library.load();
        var selected = library.select(SPEECH, window, "stored-99-window", focus);
        assertThat(selected.trace().exampleIds()).contains("mimiminu-dev#audience-devaluation");

        // New wiring fixture, NOT recovery of historical proposal text or its chosen evidence.
        var proposal = new CandidateReviewEngine.Proposal("stt-index-50", "TARGET_TREATMENT",
                "재구성 입력 연결 확인용 후보이며 당시 탐색 가설이 아니다.",
                List.of(new CandidateReviewEngine.Quote("stt-index-50", focus.get(0).text())));
        when(fixture.client.completeAsJson(anyString(), anyString(), eq(CandidateReviewEngine.Discovery.class)))
                .thenAnswer(call -> {
                    var input = json.readTree((String) call.getArgument(1));
                    var ids = new ArrayList<String>();
                    input.get("primaryIds").forEach(n -> ids.add(n.asText()));
                    return Optional.of(new CandidateReviewEngine.Discovery(ids,
                            ids.contains("stt-index-50") ? List.of(proposal) : List.of(), List.of(), false));
                });
        // Stop at the request boundary. No fabricated PASS/REVIEW response and no provider.
        when(fixture.client.completeAsJson(anyString(), anyString(), eq(CandidateReviewEngine.VerificationResult.class)))
                .thenReturn(Optional.empty());
        CandidateReviewEngine.run(fixture.client,
                new AnalysisContext(fixture.video, ContentGenre.GENERAL, lines, List.of()), 24, null, null, library);
        var system = ArgumentCaptor.forClass(String.class);
        var input = ArgumentCaptor.forClass(String.class);
        verify(fixture.client, times(1)).completeAsJson(system.capture(), input.capture(),
                eq(CandidateReviewEngine.VerificationResult.class));
        assertThat(system.getValue()).contains("사용자 승인 개발 검토 경계",
                "공개 논란 사건이 아닌 사용자 승인 개발 경계");
        assertThat(json.readTree(input.getValue()).at("/candidates/0/anchorId").asText()).isEqualTo("stt-index-50");
        assertThat(input.getValue()).doesNotContain("expectedDecision", "사용자 승인 개발 검토 경계");
        System.out.println("LOCAL_99_REFERENCE_WIRING providerCalls=0 selected=" + selected.trace().exampleIds()
                + " storedWindowSegments=" + window.size() + " historicalRequestReconstructed=false");
    }

    @Test void approvedDevelopmentBoundariesLoadAndAreRetrievedFromTheirStoredExcerpt() throws Exception {
        Path root = Path.of("../datasets/controversy");
        Path archive = root.resolve("guidelines/runtime.json");
        for (String key : new String[]{"victim-quotation", "audience-devaluation"}) {
            assumeTrue(Files.exists(root.resolve("context-sources/mimiminu-" + key + "-20261022.json")));
        }
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                archive.toAbsolutePath().toUri().toString(), 6000);
        library.load();
        assertThat(library.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        for (String key : new String[]{"victim-quotation", "audience-devaluation"}) {
            var source = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(
                    root.resolve("context-sources/mimiminu-" + key + "-20261022.json")));
            var raw = new ArrayList<ReviewInput.Segment>();
            for (var row : source.get("segments")) {
                raw.add(new ReviewInput.Segment(row.get("id").asText(), SPEECH,
                        row.get("startMs").asLong(), row.get("endMs").asLong(), row.get("text").asText(), null));
            }
            // No manual routing hints, relation records, or model-generated query.
            var selected = library.select(SPEECH, raw, "local-boundary-" + key);
            assertThat(selected.trace().exampleIds()).contains("mimiminu-dev#" + key);
            assertThat(selected.prompt()).contains("공개 논란 사건이 아닌 사용자 승인 개발 경계");
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        }
    }
}
