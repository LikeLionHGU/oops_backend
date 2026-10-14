package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceCountDiagnosticsTest {
    private LlmDecision evidence(LlmDecision d, List<LlmEvidence> e) {
        return new LlmDecision(d.segmentId(), d.decision(), d.evidenceText(), d.reason(), d.category(),
                d.target(), d.score(), d.context(), d.reading(), d.missingInformation(), e,
                d.targetType(), d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
    }
    @Test void thirteenPermittedEvidenceObjectsPassWithLimitDisabledAndCountsAreRetained() {
        var f = new CandidateReviewEngineTest();
        String[] lines = new String[12]; lines[0] = "그 집 이용자는 수준이 낮아";
        for (int i = 1; i < lines.length; i++) lines[i] = "합성 문맥 " + i;
        var c = f.context(lines);
        var ids = c.reviewInput().segments().stream().filter(s -> s.type() == com.example.oops.domain.TimelineEventType.SPEECH)
                .map(ReviewInput.Segment::id).toList();
        f.discovery(ids, f.proposal("stt-index-0", lines[0]));
        var d = f.assessment("stt-index-0", "REVIEW_REQUIRED", lines[0]);
        var e = new ArrayList<>(d.evidence());
        for (int i = 1; i < lines.length; i++) e.add(new LlmEvidence("stt-index-" + i, lines[i], "CONTEXT"));
        var assessment = evidence(d, e);
        f.verification(new Verification("candidate-1", assessment));
        var candidate = new Candidate("candidate-1", f.proposal("stt-index-0", lines[0]),
                c.reviewInput().segments().stream().filter(s -> s.type() == com.example.oops.domain.TimelineEventType.SPEECH).toList(), false, false);
        assertThat(validate(candidate, assessment, c.reviewInput(), 0).failureCode()).isNull();
        assertThat(validate(candidate, assessment, c.reviewInput(), 8).failureCode()).isEqualTo("ASSESSMENT_SHAPE");
        var analyzer = new SpeechReviewAnalyzer(f.client, true, true, 24);
        assertThat(analyzer.analyze(c)).hasSize(1);
        var pipeline = analyzer.consumeReviewResult(c).orElseThrow().diagnostics().candidatePipeline();
        assertThat(pipeline.repairCalls()).isZero();
        assertThat(pipeline.evidenceCounts()).singleElement().satisfies(t -> {
            assertThat(t.evidenceCount()).isEqualTo(13);
            assertThat(t.uniqueSegments()).isEqualTo(12);
            assertThat(t.uniqueSegmentRoles()).isEqualTo(13);
            assertThat(t.duplicateRoleEntries()).isZero();
            assertThat(t.configuredLimit()).isZero();
        });
    }
    @Test void initialAndRepairCountAreRecordedBeforeValidation() {
        var f = new CandidateReviewEngineTest();
        var d = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var repeated = new ArrayList<>(d.evidence());
        for (int i = 0; i < 7; i++) repeated.add(new LlmEvidence("stt-index-0", "그 집", "CONTEXT"));
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", evidence(d, repeated))))))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1", d)))));
        var result = run(f.client, f.context("그 집 이용자는 수준이 낮아"), 24);
        assertThat(result.diagnostics().candidatePipeline().evidenceCounts()).hasSize(2);
        var counts = result.diagnostics().candidatePipeline().evidenceCounts();
        assertThat(counts.get(0).evidenceCount()).isEqualTo(9);
        assertThat(counts.get(0).duplicateRoleEntries()).isEqualTo(6);
        assertThat(counts.get(0).validationFailure()).isEqualTo("EVIDENCE_BUDGET_EXCEEDED");
        assertThat(counts.get(1).attempt()).isEqualTo("REPAIR");
        assertThat(counts.get(1).evidenceCount()).isEqualTo(2);
    }
    @Test void oldDiagnosticJsonWithoutCountMetadataRemainsReadable() {
        var f = new CandidateReviewEngineTest(); f.discovery(List.of("stt-index-0"));
        var result = run(f.client, f.context("정상 대화"), 24);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var tree = mapper.valueToTree(result.diagnostics().candidatePipeline());
        ((tools.jackson.databind.node.ObjectNode) tree).remove("evidenceCounts");
        var restored = mapper.treeToValue(tree, CandidateReviewDiagnostics.class);
        assertThat(restored.evidenceCounts()).isEmpty();
    }
    @Test void disabledLimitDoesNotChangeOriginalEvidenceChecks() {
        var f = new CandidateReviewEngineTest(); var c = f.context("그 집 이용자는 수준이 낮아");
        var d = f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집");
        var candidate = new Candidate("candidate-1", f.proposal("stt-index-0", "그 집"), c.reviewInput().segments(), false, false);
        var duplicate = new ArrayList<>(d.evidence()); duplicate.add(d.evidence().get(0));
        assertThat(validate(candidate, evidence(d, duplicate), c.reviewInput(), 0).failureCode()).isEqualTo("DECISION_DUPLICATE_EVIDENCE");
        assertThat(verificationPrompt(0)).contains("고정 개수 상한은 없다").doesNotContain("evidence는 최대 8개");
    }
}
