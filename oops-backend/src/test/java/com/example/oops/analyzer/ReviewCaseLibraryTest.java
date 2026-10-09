package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ReviewCaseLibrary.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All records are fabricated test fixtures, NOT human-reviewed or rights-cleared production data. */
class ReviewCaseLibraryTest {
    final CandidateReviewEngineTest fixture = new CandidateReviewEngineTest();
    List<ReviewInput.Segment> raw() { return fixture.context("그 집 음식 맛이 별로야").reviewInput().segments(); }
    Case example(String id, String decision) {
        return new Case(id, "family-" + id, "a".repeat(64), "DIRECT_FEEDBACK", "TRAIN", "APPROVED", true,
                "모의 테스트의 사용권 조건", List.of("test-reviewer-1", "test-reviewer-2"), "2026-01-01",
                List.of("음식", "맛이"), "이 음식 맛은 괜찮다", "음식에 대한 개인 취향을 이야기하는 모의 상황입니다.",
                "낮은 평가를 무례하다고 읽을 수 있다는 모의 비판", "맛에 대한 취향일 뿐 대상 모욕 근거는 없습니다.",
                "이 음식 맛은 괜찮다", decision, List.of());
    }
    Case with(Case c, String key, Object value) {
        Map<String, Object> fields = JSON.readValue(JSON.writeValueAsString(c), Map.class);
        fields.put(key, value);
        return JSON.readValue(JSON.writeValueAsString(fields), Case.class);
    }
    ReviewCaseLibrary library(List<Case> cases) { return library(cases, true, 2, 2400, ""); }
    ReviewCaseLibrary library(List<Case> cases, boolean enabled, int count, int budget, String exclusions) {
        return load(JSON.writeValueAsString(new Archive("test-only-1", cases)).getBytes(StandardCharsets.UTF_8), enabled, count, budget, exclusions);
    }
    ReviewCaseLibrary load(byte[] bytes, boolean enabled, int count, int budget, String exclusions) {
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("classpath:test-cases.json")).thenReturn(new ByteArrayResource(bytes));
        var library = new ReviewCaseLibrary(resources, enabled, "classpath:test-cases.json", count, budget, exclusions);
        library.load(); return library;
    }
    @Test void emptyProductionArchiveNeverPretendsToBeLearnedData() {
        var l = new ReviewCaseLibrary(new DefaultResourceLoader(), true, "classpath:review-cases.json", 2, 2400, "");
        l.load(); var r = l.select(raw(), "b".repeat(64));
        assertThat(r.examples()).isEmpty(); assertThat(r.trace().state()).isEqualTo("EMPTY");
    }
    @Test void selectsRelevantCriticalAndNormalContrastWithoutExtraApiOrMetadata() {
        var l = library(List.of(example("a-risk", "REVIEW_REQUIRED"), example("b-risk", "REVIEW_REQUIRED"), example("c-normal", "PASS")));
        var r = l.select(raw(), "b".repeat(64));
        assertThat(r.examples()).extracting(Example::caseId).containsExactly("a-risk", "c-normal");
        assertThat(r.trace().matchedCases()).isEqualTo(3);
        assertThat(r.trace().payloadCodePoints()).isLessThanOrEqualTo(2400);
        assertThat(JSON.writeValueAsString(r.examples())).doesNotContain("reviewerIds", "rightsBasis", "familyId", "sourceFingerprint");
        assertThat(r.trace().toString()).doesNotContain("개인 취향", "모의 비판");
    }
    @Test void zeroOrOneKeywordDoesNotSelectCase() {
        var l = library(List.of(example("normal", "PASS")));
        assertThat(l.select(fixture.context("음식만 언급").reviewInput().segments(), "b").trace().state()).isEqualTo("NO_MATCH");
        assertThat(l.select(fixture.context("풍경이 좋다").reviewInput().segments(), "b").examples()).isEmpty();
    }
    @Test void rightsDraftSyntheticAndRejectedCasesCannotEnterPrompt() {
        var base = example("base", "PASS");
        for (var c : List.of(with(base, "rightsCleared", false), with(base, "reviewStatus", "DRAFT"),
                with(base, "sourceKind", "SYNTHETIC"), with(base, "reviewStatus", "REJECTED"))) {
            var r = library(List.of(c)).select(raw(), "b");
            assertThat(r.examples()).isEmpty(); assertThat(r.trace().eligibleCases()).isZero();
        }
    }
    @Test void testFamilyIsExcludedEvenIfOtherRecordClaimsTrainSplit() {
        var train = example("train", "PASS");
        var test = with(with(example("test", "PASS"), "familyId", train.familyId()), "split", "TEST");
        assertThat(library(List.of(train, test)).select(raw(), "b").examples()).isEmpty();
        assertThat(library(List.of(train), true, 2, 2400, train.familyId()).select(raw(), "b").examples()).isEmpty();
    }
    @Test void currentVideoFingerprintExcludesWholeFamilyIncludingRelatedNews() {
        var source = with(example("source", "PASS"), "sourceFingerprint", fingerprint(raw()));
        var news = with(example("news", "PASS"), "familyId", source.familyId());
        assertThat(library(List.of(source, news)).select(raw(), fingerprint(raw())).examples()).isEmpty();
    }
    @Test void onlyOneExamplePerFamilyAndStableRanking() {
        var a = example("a", "PASS"); var b = with(example("b", "PASS"), "familyId", a.familyId());
        assertThat(library(List.of(b, a)).select(raw(), "b").examples()).extracting(Example::caseId).containsExactly("a");
    }
    @Test void serializedJsonBudgetIsEnforcedWithoutCuttingEvidence() {
        var large = with(example("large", "PASS"), "context", "가".repeat(800));
        var r = library(List.of(large), true, 2, 256, "").select(raw(), "b");
        assertThat(r.examples()).isEmpty(); assertThat(r.trace().state()).isEqualTo("BUDGET_EXCLUDED");
        assertThat(r.trace().budgetSkipped()).isOne();
    }
    @Test void invalidDuplicateAndOversizedArchivesFallBackWithSafeState() {
        var a = example("a", "PASS");
        for (var list : List.of(List.of(a, a), List.of(with(a, "supportedEvidence", "없는 인용")),
                List.of(with(a, "reviewerIds", List.of("only-one"))), List.of(with(a, "sourceFingerprint", "bad")))) {
            var r = library(list).select(raw(), "b");
            assertThat(r.examples()).isEmpty(); assertThat(r.trace().state()).isEqualTo("ARCHIVE_UNAVAILABLE_OR_INVALID");
        }
        assertThat(load("{bad".getBytes(StandardCharsets.UTF_8), true, 2, 2400, "").select(raw(), "b").trace().state())
                .isEqualTo("ARCHIVE_UNAVAILABLE_OR_INVALID");
        assertThat(load(new byte[MAX_BYTES + 1], true, 2, 2400, "").select(raw(), "b").trace().state()).isEqualTo("ARCHIVE_TOO_LARGE");
    }
    @Test void disabledLibraryMakesNoResourceRequestAndRemoteLocationsAreRejected() {
        var resources = mock(ResourceLoader.class);
        var l = new ReviewCaseLibrary(resources, false, "classpath:unused.json", 2, 2400, "");
        l.load(); assertThat(l.select(raw(), "b").trace().state()).isEqualTo("DISABLED"); verifyNoInteractions(resources);
        assertThatThrownBy(() -> new ReviewCaseLibrary(resources, true, "https://example.com/cases.json", 2, 2400, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewCaseLibrary(resources, true, "file://remote-host/cases.json", 2, 2400, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewCaseLibrary(resources, true, "classpath:cases.json", 4, 2400, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fingerprintsIgnorePunctuationAndSegmentIdsButNotContent() {
        assertThat(fingerprint(fixture.context("음식, 맛!").reviewInput().segments()))
                .isEqualTo(fingerprint(fixture.context("음식 맛").reviewInput().segments()));
        assertThat(fingerprint(raw())).isNotEqualTo(fingerprint(fixture.context("다른 내용").reviewInput().segments()));
    }
    @Test void referencesOnlyEnterDiscoveryNotIndependentVerification() {
        var l = library(List.of(example("past-normal", "PASS")));
        fixture.discovery(List.of("stt-index-0"), fixture.proposal("stt-index-0", "그 집"));
        fixture.verification(new Verification("candidate-1", fixture.assessment("stt-index-0", "PASS", "그 집")));
        var r = run(fixture.client, fixture.context("그 집 음식 맛이 별로야"), 24, null, l);
        assertThat(r.findings()).isEmpty(); assertThat(r.diagnostics().candidatePipeline().caseRetrieval()).singleElement()
                .extracting(Trace::state).isEqualTo("SELECTED");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class), user = ArgumentCaptor.forClass(String.class);
        verify(fixture.client).completeAsJson(system.capture(), user.capture(), eq(Discovery.class));
        assertThat(system.getValue()).contains(CASE_REFERENCE_CONTRACT);
        assertThat(user.getValue()).contains("referenceCases", "past-normal");
        verify(fixture.client).completeAsJson(eq(VERIFICATION_PROMPT), argThat(s -> !s.contains("referenceCases") && !s.contains("past-normal")), eq(VerificationResult.class));
        verify(fixture.client, times(2)).completeAsJson(anyString(), anyString(), any());
    }
    @Test void unavailableArchiveKeepsBaseRequestAndCoverageWithoutInventingPartialFailure() {
        var l = load("bad-json".getBytes(StandardCharsets.UTF_8), true, 2, 2400, "");
        fixture.discovery(List.of("stt-index-0"));
        var r = run(fixture.client, fixture.context("음식 맛"), 24, null, l);
        assertThat(r.status()).isEqualTo(com.example.oops.domain.AnalyzerStatus.SUCCESS);
        assertThat(r.diagnostics().candidatePipeline().caseRetrieval().get(0).state()).isEqualTo("ARCHIVE_UNAVAILABLE_OR_INVALID");
        verify(fixture.client).completeAsJson(eq(DISCOVERY_PROMPT), argThat(s -> !s.contains("referenceCases")), eq(Discovery.class));
    }
    @Test void pastCaseQuoteCannotBeUsedAsCurrentVideoEvidence() {
        var l = library(List.of(example("past", "PASS")));
        fixture.discovery(List.of("stt-index-0"), new Proposal("stt-index-0", "TARGET_TREATMENT", "과거 사례를 잘못 복사한 후보",
                List.of(new Quote("stt-index-0", "이 음식 맛은 괜찮다"))));
        var r = run(fixture.client, fixture.context("음식 맛이 별로야"), 24, null, l);
        assertThat(r.findings()).isEmpty(); assertThat(r.diagnostics().candidatePipeline().invalidProposals()).isOne();
        verify(fixture.client, never()).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
    @Test void replayPrintsOnlyFingerprintAndRetrievalMetadataNotCurrentText() {
        var output = ReviewCaseReplay.inspect(new ReviewCaseReplay.Input("own-family", List.of("그 집 음식 맛이 별로야")),
                library(List.of(example("a", "PASS"))));
        assertThat(output.retrieval().caseIds()).containsExactly("a");
        assertThat(JSON.writeValueAsString(output)).doesNotContain("별로야", "criticClaim", "rightsBasis");
        var a = example("a", "PASS");
        assertThat(ReviewCaseReplay.inspect(new ReviewCaseReplay.Input(a.familyId(), List.of("그 집 음식 맛이 별로야")),
                library(List.of(a))).retrieval().caseIds()).isEmpty();
        assertThatThrownBy(() -> ReviewCaseReplay.inspect(new ReviewCaseReplay.Input(null, List.of("음식")), library(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
