package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline grounding/flow tests, not semantic fact-check accuracy. */
class FactVerificationContractTest {
    private final Video video = Video.builder().filename("offline.mp4").build();
    private final String raw = "회사는 2019년에 설립됐다";
    private final List<TranscriptSegment> transcript = List.of(new TranscriptSegment(video, 0, 1000, raw));
    private final EntityCheckAnalyzer.Claim claim = new EntityCheckAnalyzer.Claim(0, raw, "회사", "DATE",
            List.of("회사 설립"), List.of(new EntityCheckAnalyzer.ClaimEvidence(0, raw)),
            "ASSERTION", "2019년", "설립 이력 대조");
    private final List<EntityCheckAnalyzer.Evidence> evidence = List.of(
            new EntityCheckAnalyzer.Evidence(new NewsSearchClient.NewsItem("회사 설립", "2020년 설립", "",
                    "https://example.com/1"), ReferenceSourceType.values()[0]),
            new EntityCheckAnalyzer.Evidence(new NewsSearchClient.NewsItem("회사 이력", "2019년 설립", "",
                    "https://example.com/2"), ReferenceSourceType.values()[0]));
    private EntityCheckAnalyzer.Verdict verdict(String type, Double score, String correction, List<Integer> sources,
                                               String quote, List<EntityCheckAnalyzer.SourceEvidence> quotes,
                                               List<String> missing) {
        return new EntityCheckAnalyzer.Verdict(type, score, "영상과 자료의 설립 연도가 다릅니다.",
                correction, sources, quote, quotes, missing);
    }
    private EntityCheckAnalyzer.Verdict risk() {
        return verdict("FACT_ERROR", .7, "당시 해당 지점은 없습니다", List.of(0), raw,
                List.of(new EntityCheckAnalyzer.SourceEvidence(0, "2020년 설립")), List.of());
    }
    private boolean valid(EntityCheckAnalyzer.Verdict v) {
        return EntityCheckAnalyzer.validVerdict(v, transcript, claim, evidence);
    }
    @Test void acceptsExactGroundingWithoutAbsenceSubstringHeuristic() { assertThat(valid(risk())).isTrue(); }
    @Test void rejectsUnknownTypeAndNonfinitePriority() {
        var r = risk();
        assertThat(valid(verdict("MOCKERY", .7, null, r.sources(), raw, r.sourceEvidence(), List.of()))).isFalse();
        assertThat(valid(verdict("FACT_ERROR", Double.NaN, null, r.sources(), raw, r.sourceEvidence(), List.of()))).isFalse();
        assertThat(valid(verdict("FACT_ERROR", 1.1, null, r.sources(), raw, r.sourceEvidence(), List.of()))).isFalse();
    }
    @Test void rejectsInventedVideoAndSourceQuotes() {
        assertThat(valid(verdict("FACT_ERROR", .7, null, List.of(0), "2020년이었다",
                risk().sourceEvidence(), List.of()))).isFalse();
        assertThat(valid(verdict("FACT_ERROR", .7, null, List.of(0), raw,
                List.of(new EntityCheckAnalyzer.SourceEvidence(0, "2021년 설립")), List.of()))).isFalse();
    }
    @Test void rejectsInvalidDuplicateAndUnquotedReferences() {
        assertThat(valid(verdict("FACT_ERROR", .7, null, List.of(9), raw,
                List.of(new EntityCheckAnalyzer.SourceEvidence(9, "2020년 설립")), List.of()))).isFalse();
        assertThat(valid(verdict("FACT_ERROR", .7, null, List.of(0, 0), raw, risk().sourceEvidence(), List.of()))).isFalse();
        assertThat(valid(verdict("FACT_ERROR", .7, null, List.of(0, 1), raw, risk().sourceEvidence(), List.of()))).isFalse();
    }
    @Test void conflictRequiresTwoGroundedSourcesAndNoCorrection() {
        assertThat(valid(verdict("UNVERIFIED_CLAIM", .7, null, List.of(0), raw,
                risk().sourceEvidence(), List.of()))).isFalse();
        assertThat(valid(verdict("UNVERIFIED_CLAIM", .7, null, List.of(0, 1), raw, List.of(
                new EntityCheckAnalyzer.SourceEvidence(0, "2020년 설립"),
                new EntityCheckAnalyzer.SourceEvidence(1, "2019년 설립")), List.of()))).isTrue();
    }
    @Test void limitedStatesRequireMissingInformationAndNoRiskScore() {
        for (String type : List.of("NOT_COMPARABLE", "INSUFFICIENT_EVIDENCE")) {
            assertThat(valid(verdict(type, null, null, List.of(), raw, List.of(), List.of("촬영 날짜")))).isTrue();
            assertThat(valid(verdict(type, null, null, List.of(), raw, List.of(), List.of()))).isFalse();
        }
        assertThat(valid(verdict("OK", null, null, List.of(), raw, List.of(), List.of()))).isFalse();
    }
    private EntityCheckAnalyzer analyzer(EntityCheckAnalyzer.Verdict result, boolean emptySearch) {
        var ai = mock(OpenAiClient.class);
        var search = mock(NewsSearchClient.class);
        var classifier = mock(SourceClassifier.class);
        when(search.isEnabled()).thenReturn(true);
        when(search.searchArchive(anyString(), anyInt())).thenReturn(emptySearch ? List.of() :
                evidence.stream().map(EntityCheckAnalyzer.Evidence::item).toList());
        when(classifier.classify(any())).thenReturn(ReferenceSourceType.values()[0]);
        when(ai.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.ClaimResult.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.ClaimResult(List.of(claim))));
        when(ai.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.ofNullable(result));
        return new EntityCheckAnalyzer(ai, List.of(search), classifier);
    }
    @Test void limitedAndFailedComparisonsProduceCoverageNotCards() {
        var context = new AnalysisContext(video, ContentGenre.GENERAL, transcript, List.of());
        for (var analyzer : List.of(analyzer(null, false), analyzer(null, true),
                analyzer(verdict("INSUFFICIENT_EVIDENCE", null, null, List.of(), raw,
                        List.of(), List.of("촬영 날짜")), false))) {
            assertThat(analyzer.analyze(context)).isEmpty();
            assertThat(analyzer.consumeCoverageNotice(context)).isPresent();
            assertThat(analyzer.consumeCoverageNotice(context)).isEmpty();
        }
    }
    @Test void groundedRiskPublishesAndAbsenceWordingDoesNotSuppressIt() {
        var analyzer = analyzer(risk(), false);
        var findings = analyzer.analyze(new AnalysisContext(video, ContentGenre.GENERAL, transcript, List.of()));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getReason()).contains("없습니다");
        assertThat(findings.get(0).getTarget()).isEqualTo("회사");
        assertThat(findings.get(0).getReferences()).hasSize(1);
    }
}
