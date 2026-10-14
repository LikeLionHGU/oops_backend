package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Input continuity contracts only; these mocks do not measure model detection accuracy. */
class FrozenCandidateContextTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void repairReusesFrozenRawProposalAndReferencesAndRecordsChangedDecision() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var original = new CandidateReferenceRepairTest().badId(f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집"));
        var repaired = f.assessment("stt-index-0", "PASS", "그 집");
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenReturn(
                Optional.of(new VerificationResult(List.of(new Verification("candidate-1", original)))),
                Optional.of(new VerificationResult(List.of(new Verification("candidate-1", repaired)))));
        var helper = new ContextExampleSelectionTest();
        var library = helper.library(helper.archive(2), 1, 3000, "");
        var result = run(f.client, f.context("그 집은 조용하다"), 24, null, null, library);
        var systems = ArgumentCaptor.forClass(String.class);
        var requests = ArgumentCaptor.forClass(String.class);
        verify(f.client, times(2)).completeAsJson(systems.capture(), requests.capture(), eq(VerificationResult.class));
        var first = json.readTree(requests.getAllValues().get(0));
        var second = json.readTree(requests.getAllValues().get(1));
        assertThat(second.get("raw")).isEqualTo(first.get("raw"));
        assertThat(second.get("candidates")).isEqualTo(first.get("candidates"));
        assertThat(second.has("repair")).isTrue();
        assertThat(systems.getAllValues().get(1)).isEqualTo(systems.getAllValues().get(0) + "\n" + ID_REPAIR_PROMPT);
        assertThat(requests.getAllValues()).allSatisfy(r -> assertThat(r).doesNotContain("invented-id", "없는 근거"));
        var pipeline = result.diagnostics().candidatePipeline();
        assertThat(pipeline.contextSelections()).hasSize(1);
        assertThat(pipeline.repairs()).singleElement().satisfies(t -> {
            assertThat(t.initialDecision()).isEqualTo("REVIEW_REQUIRED");
            assertThat(t.repairedDecision()).isEqualTo("PASS");
            assertThat(t.decisionChanged()).isTrue();
            assertThat(t.state()).isEqualTo("RECOVERED");
        });
        assertThat(result.findings()).isEmpty(); // Never force the invalid initial verdict onto a card.
        var snapshot = pipeline.candidates().get(0).inputSnapshot();
        assertThat(snapshot.rawSha256()).isEqualTo(ReviewGuidelineLibrary.retrievalHash(json.writeValueAsString(first.get("raw"))));
        assertThat(snapshot.referenceSha256()).isEqualTo(ReviewGuidelineLibrary.retrievalHash(
                systems.getAllValues().get(0).substring(VERIFICATION_PROMPT.length())));
        assertThat(snapshot.proposalSha256()).hasSize(64);
    }

    @Test void olderStoredTraceAndRepairJsonRemainReadableWithoutNewFields() {
        var old = new CandidateReviewDiagnostics.Trace("c", "s", "TARGET_TREATMENT", "PASS");
        String oldJson = json.writeValueAsString(old).replace(",\"inputSnapshot\":null", "");
        assertThat(json.readValue(oldJson, CandidateReviewDiagnostics.Trace.class).inputSnapshot()).isNull();
        var repair = json.readValue("{\"candidateId\":\"c\",\"initialFailureCode\":\"TARGET_REQUIRED\","
                + "\"state\":\"RECOVERED\",\"finalFailureCode\":null}", CandidateReviewDiagnostics.RepairTrace.class);
        assertThat(repair.initialDecision()).isNull();
        assertThat(repair.repairedDecision()).isNull();
        assertThat(repair.decisionChanged()).isNull();
    }

    @Test void differentCandidateWindowsAreNeverUnionedIntoOneVerificationInput() {
        var f = new CandidateReviewEngineTest();
        List<com.example.oops.domain.TranscriptSegment> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) lines.add(new com.example.oops.domain.TranscriptSegment(
                f.video, i * 2000L, i * 2000L + 1500, "그 집 대화 " + i));
        var context = new AnalysisContext(f.video, com.example.oops.domain.ContentGenre.GENERAL, lines, List.of());
        when(f.client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenAnswer(invocation -> {
            var request = json.readTree((String) invocation.getArgument(1));
            List<String> ids = new ArrayList<>(); request.get("primaryIds").forEach(n -> ids.add(n.asText()));
            var proposals = ids.stream().filter(id -> id.equals("stt-index-0") || id.equals("stt-index-39"))
                    .map(id -> f.proposal(id, "그 집")).toList();
            return Optional.of(new Discovery(ids, proposals, List.of(), false));
        });
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(invocation -> {
            var request = json.readTree((String) invocation.getArgument(1));
            List<String> rawIds = new ArrayList<>(); request.get("raw").forEach(n -> rawIds.add(n.get("id").asText()));
            List<Verification> results = new ArrayList<>();
            for (var candidate : request.get("candidates")) {
                List<String> allowed = new ArrayList<>(); candidate.get("segmentIds").forEach(n -> allowed.add(n.asText()));
                assertThat(rawIds).containsExactlyElementsOf(allowed);
                results.add(new Verification(candidate.get("candidateId").asText(),
                        f.assessment(candidate.get("anchorId").asText(), "PASS", "그 집")));
            }
            return Optional.of(new VerificationResult(results));
        });
        var pipeline = run(f.client, context, 24).diagnostics().candidatePipeline();
        assertThat(pipeline.candidates()).hasSize(2);
        assertThat(pipeline.verificationCalls()).isEqualTo(2);
        assertThat(pipeline.candidates().stream().map(c -> c.inputSnapshot().rawSha256()).distinct()).hasSize(2);
    }

    @Test void candidateDoesNotKeepAMutableRawListFromTheCaller() {
        var raw = new ArrayList<>(new CandidateReviewEngineTest().context("그 집").reviewInput().segments());
        var c = new Candidate("c", new CandidateReviewEngineTest().proposal("stt-index-0", "그 집"), raw, false, false);
        raw.clear();
        assertThat(c.raw()).isNotEmpty();
        assertThatThrownBy(() -> c.raw().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
