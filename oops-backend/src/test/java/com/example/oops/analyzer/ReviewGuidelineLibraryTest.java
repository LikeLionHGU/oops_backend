package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.domain.TimelineEventType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic rules only: no assertion of actual human validation or model accuracy. */
class ReviewGuidelineLibraryTest {
    @Test void localCompiledArchiveLoadsWithinProductionBudgetWhenPresent() throws Exception {
        var path = java.nio.file.Path.of("../datasets/controversy/guidelines/runtime.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(path));
        var l = library(java.nio.file.Files.readAllBytes(path), 6000);
        for (var channel : List.of(SPEECH, CAPTION)) {
            assertThat(l.trace(channel).state()).isEqualTo("READY_WORKING_REFERENCE");
            assertThat(l.trace(channel).contextDictionarySha256()).matches("[0-9a-f]{64}");
            assertThat(l.trace(channel).payloadCodePoints()).isLessThanOrEqualTo(6000);
        }
    }
    @Test void dictionaryArchiveRequiresFingerprintAndKeepsTypedInterpretations() {
        var a = enrichedArchive(); var rule = a.guidelines().get(0); var old = rule.referenceContexts().get(0);
        var ref = new ReferenceContext(old.sourceCaseId(), old.coverage(), old.flow(),
                List.of("독립 비판 하나", "독립 비판 둘", "독립 비판 셋"), old.missingContext(),
                List.of(new SourceInterpretation("AUTHOR_INTERPRETATION", "합성 기사 해석")));
        var updated = new Rule(rule.id(), rule.axis(), rule.channels(), rule.condition(), rule.normalContrast(),
                rule.requiredEvidence(), rule.missingContext(), rule.sourceCaseIds(), List.of(ref));
        var dictionary = new Archive("review-guidelines-3", "synthetic-dictionary", a.status(), false,
                a.usage(), a.sourceBundleSha256(), a.refreshOrDeleteBy(), a.sourceCases(), List.of(updated), "b".repeat(64));
        var l = library(dictionary);
        assertThat(l.trace(SPEECH).contextDictionarySha256()).isEqualTo("b".repeat(64));
        assertThat(l.prompt(SPEECH)).contains("독립 비판 셋", "AUTHOR_INTERPRETATION", "합성 기사 해석")
                .doesNotContain("case-1", "family-1");
        var invalid = new Archive(dictionary.schemaVersion(), dictionary.version(), dictionary.status(), false,
                dictionary.usage(), dictionary.sourceBundleSha256(), dictionary.refreshOrDeleteBy(),
                dictionary.sourceCases(), dictionary.guidelines(), null);
        assertThat(library(invalid).prompt(SPEECH)).isEmpty();
    }
    Archive enrichedArchive() {
        var rule = archive().guidelines().get(0);
        var reference = new ReferenceContext("case-1", "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT",
                List.of("선택지 부재", "대체 생활을 낮추는 비교"), List.of("생활을 하대한다는 비판 가설"), List.of("억양 미확인"));
        var enriched = new Rule(rule.id(), rule.axis(), rule.channels(), rule.condition(), rule.normalContrast(),
                rule.requiredEvidence(), rule.missingContext(), rule.sourceCaseIds(), List.of(reference));
        Map<String,Object> map = JSON.readValue(JSON.writeValueAsString(archive()), Map.class);
        map.put("schemaVersion", "review-guidelines-2"); map.put("guidelines", List.of(enriched));
        return JSON.readValue(JSON.writeValueAsString(map), Archive.class);
    }
    Archive archive() {
        return new Archive("review-guidelines-1", "synthetic-v1", "WORKING_REFERENCE_NOT_VALIDATED", false,
                "REFERENCE_ONLY_CURRENT_INPUT_EVIDENCE_REQUIRED", "a".repeat(64), OffsetDateTime.now().plusDays(10).toString(),
                List.of(new SourceCase("case-1", "family-1", "UNREVIEWED_DRAFT")),
                List.of(new Rule("mechanism-1", "TARGET_TREATMENT", List.of("SPEECH", "CAPTION"),
                        "대상과 낮추는 평가의 연결", "단순 불만 제외", "현재 원문 인용", "대상 불명확하면 보류", List.of("case-1"))));
    }
    ReviewGuidelineLibrary library(Archive archive) { return library(JSON.writeValueAsString(archive).getBytes(StandardCharsets.UTF_8), 6000); }
    ReviewGuidelineLibrary library(byte[] bytes, int budget) {
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("classpath:test.json")).thenReturn(new ByteArrayResource(bytes));
        var library = new ReviewGuidelineLibrary(resources, true, "classpath:test.json", budget);
        library.load(); return library;
    }
    Archive with(String field, Object value) {
        Map<String,Object> map = JSON.readValue(JSON.writeValueAsString(archive()), Map.class);
        map.put(field,value); return JSON.readValue(JSON.writeValueAsString(map), Archive.class);
    }
    @Test void compactPayloadContainsMechanismNotSourceCaseOrComment() {
        var l = library(archive());
        assertThat(l.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        assertThat(l.trace(SPEECH).guidelineIds()).containsExactly("mechanism-1");
        assertThat(l.prompt(SPEECH)).contains("reviewGuidelines", "현재 원문").doesNotContain("case-1", "family-1", "sourceCaseIds");
        assertThat(l.trace(SPEECH).toString()).doesNotContain("현재 원문 인용");
    }
    @Test void expiredOrInvalidOrOversizedDoesNotEnterPrompt() {
        var expired = library(with("refreshOrDeleteBy", OffsetDateTime.now().minusDays(1).toString()));
        assertThat(expired.trace(SPEECH).state()).isEqualTo("EXPIRED");
        assertThat(expired.unavailableNotice(SPEECH)).hasValueSatisfying(n -> assertThat(n).contains("EXPIRED"));
        for (var a : List.of(with("humanValidated", true), with("schemaVersion", "wrong"), with("sourceCases", List.of()))) {
            assertThat(library(a).prompt(SPEECH)).isEmpty();
        }
        assertThat(library(new byte[1_048_577], 6000).trace(SPEECH).state()).isEqualTo("ARCHIVE_TOO_LARGE");
        assertThat(library("{broken".getBytes(StandardCharsets.UTF_8),6000).examples(SPEECH)).isEmpty();
    }
    @Test void channelsFilterWithoutKeywordBlocking() {
        var a = archive(); var rule = a.guidelines().get(0);
        var caption = new Rule(rule.id(),rule.axis(),List.of("CAPTION"),rule.condition(),rule.normalContrast(),rule.requiredEvidence(),rule.missingContext(),rule.sourceCaseIds());
        var l = library(with("guidelines",List.of(caption)));
        assertThat(l.examples(SPEECH)).isEmpty(); assertThat(l.examples(CAPTION)).hasSize(1);
    }
    @Test void noAutomaticApprovalOrUnmappedSourceAcceptance() {
        var l = library(with("sourceCases",List.of(new SourceCase("case-2","family-2","UNREVIEWED_DRAFT"))));
        assertThat(l.trace(SPEECH).state()).isEqualTo("INVALID_ARCHIVE");
    }
    @Test void oversizedPayloadIsRejectedRatherThanCuttingOffCriteria() {
        var longRule = new Rule("mechanism-1", "TARGET_TREATMENT", List.of("SPEECH"),
                "가".repeat(350), "나".repeat(350), "다".repeat(350), "라".repeat(350), List.of("case-1"));
        var data = JSON.writeValueAsString(with("guidelines",List.of(longRule))).getBytes(StandardCharsets.UTF_8);
        var l = library(data,256);
        assertThat(l.trace(SPEECH).state()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(l.prompt(SPEECH)).isEmpty();
    }
    @Test void disabledAndRemoteFileSourceBoundaries() {
        var resources = mock(ResourceLoader.class);
        var l = new ReviewGuidelineLibrary(resources,false,"classpath:unused",6000);
        l.load(); assertThat(l.trace(SPEECH).state()).isEqualTo("DISABLED"); verifyNoInteractions(resources);
        for (String path : List.of("https://example.org", "file://remote/archive.json"))
            assertThatThrownBy(() -> new ReviewGuidelineLibrary(resources,true,path,6000)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ReviewGuidelineLibrary(resources,true,"file:///tmp/local-guidelines.json",6000)).doesNotThrowAnyException();
    }
    @Test void actualCandidateRequestsIncludeGuidelinesInDiscoveryAndVerification() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0","그 집"));
        f.verification(new Verification("candidate-1", f.assessment("stt-index-0","PASS","그 집")));
        var result = run(f.client,f.context("그 집은 조용하다"),24,null,null,library(enrichedArchive()));
        var systems = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client,times(2)).completeAsJson(systems.capture(),anyString(),any());
        assertThat(systems.getAllValues()).allSatisfy(s -> assertThat(s).contains("reviewGuidelines", "mechanism-1",
                "대체 생활을 낮추는 비교", "생활을 하대한다는 비판 가설", "억양 미확인").doesNotContain("case-1", "family-1"));
        assertThat(result.diagnostics().candidatePipeline().guidelineReference().state()).isEqualTo("READY_WORKING_REFERENCE");
    }
    @Test void enrichedReferencesMustMatchSourcesAndPreserveUnknowns() {
        var a = enrichedArchive(); var l = library(a);
        assertThat(l.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        assertThat(l.examples(SPEECH).get(0).referenceContexts()).hasSize(1);
        assertThat(l.trace(SPEECH).referenceContextCount()).isEqualTo(1);
        Map<String,Object> map = JSON.readValue(JSON.writeValueAsString(a), Map.class);
        var rule = a.guidelines().get(0);
        for (var refs : List.of(List.<ReferenceContext>of(), List.of(new ReferenceContext("absent",
                "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT", List.of("流れ"), List.of(), List.of("未確認"))),
                List.of(new ReferenceContext("case-1", "CONFIRMED", List.of("流れ"), List.of(), List.of("未確認"))))) {
            map.put("guidelines", List.of(new Rule(rule.id(), rule.axis(), rule.channels(), rule.condition(),
                    rule.normalContrast(), rule.requiredEvidence(), rule.missingContext(), rule.sourceCaseIds(), refs)));
            assertThat(library(JSON.readValue(JSON.writeValueAsString(map), Archive.class)).trace(SPEECH).state()).isEqualTo("INVALID_ARCHIVE");
        }
        map.put("guidelines", archive().guidelines());
        assertThat(library(JSON.readValue(JSON.writeValueAsString(map), Archive.class)).prompt(SPEECH)).isEmpty();
    }
}
