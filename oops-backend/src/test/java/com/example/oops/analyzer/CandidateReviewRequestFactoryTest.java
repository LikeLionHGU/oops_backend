package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CandidateReviewRequestFactoryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void discoveryAssemblyPreservesSharedPromptAndAddsOnlyTheSelectedApprovedCasePayload() {
        var fixture = new CandidateReviewEngineTest();
        var context = fixture.context("현재 영상 원문");
        var batch = DialogueReadingPage.plan(context.reviewInput()).get(0);
        var raw = DialogueReadingPage.raw(batch);
        var units = CandidateContextWindow.conversationUnits(batch);
        var example = new ReviewCaseLibrary.Example("approved-case", "사례 표현", "상황 흐름",
                "비판 가설", "정상 해석", "원문 근거", "REVIEW_REQUIRED", List.of());

        var built = CandidateReviewRequestFactory.discovery(CandidateReviewEngine.REVISION, false,
                "\nselected-guideline", batch.primary().stream().map(ReviewInput.Segment::id).toList(),
                raw, units, units, batch.contextLimited(), List.of(example));

        assertThat(built.system()).isEqualTo(CandidateReviewEngine.DISCOVERY_PROMPT + "\nselected-guideline\n"
                + CandidateReviewEngine.CASE_REFERENCE_CONTRACT);
        assertThat(built.hasReferences()).isTrue();
        var legacyShape = new LinkedHashMap<>(Map.of(
                "promptRevision", CandidateReviewEngine.REVISION,
                "primaryIds", batch.primary().stream().map(ReviewInput.Segment::id).toList(),
                "raw", CandidateReviewEngine.compact(raw),
                "windows", CandidateReviewEngine.compactWindows(units),
                "targetWindows", CandidateReviewEngine.compactWindows(units),
                "contextLimited", batch.contextLimited()));
        legacyShape.put("readingPage", DialogueReadingPage.metadata());
        legacyShape.put("referenceCases", List.of(example));
        assertThat(built.input()).isEqualTo(JSON.writeValueAsString(legacyShape));
        var input = JSON.readTree(built.input());
        assertThat(input.path("referenceCases").get(0).path("caseId").asText()).isEqualTo("approved-case");
        assertThat(input.path("raw").get(0).path("text").asText()).isEqualTo("현재 영상 원문");
        assertThat(built.input().codePointCount(0, built.input().length())).isLessThan(DialogueReadingPage.MAX_INPUT_CODE_POINTS);
        assertThat(built.system().codePointCount(0, built.system().length())).isLessThan(20_000);
    }

    @Test void verificationAssemblyKeepsTheExistingBaselinePromptAndCandidateScopedInput() {
        var fixture = new CandidateReviewEngineTest();
        var context = fixture.context("anchor text", "permitted context");
        var speechRaw = context.reviewInput().segments().stream()
                .filter(segment -> segment.type() == com.example.oops.domain.TimelineEventType.SPEECH).toList();
        var candidate = new CandidateReviewEngine.Candidate("candidate-1",
                fixture.proposal("stt-index-0", "anchor text"), speechRaw, false, false);
        var features = new CandidateReviewEngine.VerificationFeatures(false, false, false);
        String expected = CandidateReviewEngine.verificationSystem(
                CandidateReviewEngine.verificationPrompt(0) + "\nselected-guideline", features);

        String system = CandidateReviewRequestFactory.verificationSystem(candidate, 0, "\nselected-guideline", features);
        String input = CandidateReviewRequestFactory.verification(List.of(candidate), null, null, false);

        assertThat(system).isEqualTo(expected);
        var request = JSON.readTree(input);
        assertThat(request.path("raw").size()).isEqualTo(2);
        assertThat(request.path("candidates").size()).isOne();
        assertThat(request.path("candidates").get(0).path("candidateId").asText()).isEqualTo("candidate-1");
        assertThat(input.codePointCount(0, input.length())).isLessThan(24_000);
        assertThat(system.codePointCount(0, system.length())).isLessThan(20_000);
    }
}
