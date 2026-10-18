package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Offline schema/branch tests; not a comprehension benchmark. */
class SourceFirstJudgmentTest {
    JsonNode raw() { var n = (ObjectNode) new SourceFirstReviewBoundaryTest().source(); n.putNull("sourceReading"); return n; }
    SourceFirstReviewBoundary.Snapshot snapshot(JsonNode in) { return new SourceFirstReviewBoundaryTest().snapshot(in); }
    SourceFirstJudgmentContract.Outcome outcome(String hash, String function, String content, String decision, String basis, String claim) {
        return new SourceFirstJudgmentContract.Outcome("b", new SourceFirstReviewBoundary.ReadingUse(hash, function, content, null, List.of()),
                decision, basis, claim, List.of("a", "b"), "원문을 근거로 한 모의 판단", "정상 해석을 대조한 모의 설명", List.of());
    }
    @Test void matchingReadingDoesNotForcePassAndExpressionReviewIsPossible() {
        var source = raw(); var frozen = snapshot(source); var in = SourceFirstReviewBoundary.judgmentSource(source, frozen);
        for (var out : List.of(outcome(frozen.readingSha256(), "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "PASS", "NONE", ""),
                outcome(frozen.readingSha256(), "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "REVIEW_REQUIRED", "EXPRESSION_CONTENT", "별도 표현 문제의 모의 근거"))) {
            var assessed = SourceFirstJudgmentContract.assess(in, frozen, out);
            assertThat(assessed.state()).isEqualTo("ASSESSMENT_RECORDED"); assertThat(assessed.publicationAuthorized()).isFalse();
        }
    }
    @Test void actualRequestBasisCannotUseDefinitionDeclaration() {
        var source = raw();
        var out = outcome(null, "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "REVIEW_REQUIRED", "ACTUAL_REQUEST", "실제 상대 요청이라는 모의 주장");
        assertThat(SourceFirstJudgmentContract.assess(source, null, out).failure()).isEqualTo("ACTUAL_REQUEST_READING_CONFLICT");
    }
    @Test void readingChallengeIsHeldRatherThanOverwrittenOrPublished() {
        var source = raw(); var frozen = snapshot(source); var in = SourceFirstReviewBoundary.judgmentSource(source, frozen);
        var use = new SourceFirstReviewBoundary.ReadingUse(frozen.readingSha256(), "DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT", "원문에 기반한 모의 이의", List.of("b"));
        var out = new SourceFirstJudgmentContract.Outcome("b", use, "REVIEW_REQUIRED", "ACTUAL_REQUEST", "요청의 모의 근거", List.of("b"), "판단 이유", "대조 설명", List.of());
        assertThat(SourceFirstJudgmentContract.assess(in, frozen, out).state()).isEqualTo("SOURCE_RECHECK_REQUIRED");
        assertThat(frozen.readingJson()).contains("DEFINITION_OR_EXPANSION");
    }
    @Test void foreignEvidenceAndWrongSnapshotCannotBeAccepted() {
        var source = raw(); var frozen = snapshot(source); var in = SourceFirstReviewBoundary.judgmentSource(source, frozen);
        var good = outcome(frozen.readingSha256(), "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "PASS", "NONE", "");
        var bad = new SourceFirstJudgmentContract.Outcome("b", good.readingUse(), "PASS", "NONE", "", List.of("outside"), good.reason(), good.alternativeInterpretation(), List.of());
        assertThat(SourceFirstJudgmentContract.assess(in, frozen, bad).state()).isEqualTo("CONTRACT_INVALID");
        ((ObjectNode) in.path("sourceReading")).put("readingSha256", "changed");
        assertThat(SourceFirstJudgmentContract.assess(in, frozen, good).failure()).isEqualTo("SNAPSHOT_INPUT_MISMATCH");
    }
    @Test void rawOnlyHasNoFrozenBindingAndMalformedResponseStaysInvalid() {
        var source = raw();
        assertThat(SourceFirstJudgmentContract.assess(source, null, outcome(null, "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "PASS", "NONE", "")).state())
                .isEqualTo("ASSESSMENT_RECORDED");
        assertThat(SourceFirstJudgmentContract.assess(source, null, null).state()).isEqualTo("CONTRACT_INVALID");
        assertThat(SourceFirstJudgmentContract.format().at("/json_schema/strict").asBoolean()).isTrue();
    }
    JsonNode bundle() {
        var bundle = JSON.createObjectNode().put("version", SourceFirstReviewBoundary.VERSION); var rows = bundle.putArray("stages");
        for (String id : RelationGuidedVerificationTrial.IDS) {
            var source = raw(); var frozen = snapshot(source); var row = rows.addObject().put("caseId", id);
            row.set("frozenReading", JSON.valueToTree(frozen)); row.set("freshJudgmentSource", SourceFirstReviewBoundary.judgmentSource(source, frozen));
        }
        return bundle;
    }
    @Test void trialPairsSameRawAndOnlyChangesIndependentReading() {
        var p = SourceFirstJudgmentTrial.build(bundle()); SourceFirstJudgmentTrial.validate(p);
        assertThat(p.requests()).hasSize(35); assertThat(p.reservationEstimatedUsd()).isLessThan(.50);
        assertThat(p.requests()).filteredOn(r -> r.arm().equals("CORRUPTED_READING")).hasSize(4);
        for (int i = 1; i < 31; i += 2) {
            var a = p.requests().get(i); var b = p.requests().get(i + 1);
            var x = (ObjectNode) a.input().deepCopy(); var y = (ObjectNode) b.input().deepCopy(); x.remove("sourceReading"); y.remove("sourceReading");
            assertThat(x).isEqualTo(y);
            assertThat(a.body().toString() + b.body()).doesNotContain("expectedDecision", "hypothesisNotEvidence", "judgmentToCheck", "contextReference");
        }
    }
    @Test void semanticConflictInFreeTextIsNotProvenByStructuralChecks() {
        // Deliberately exposes the limitation: a model can mislabel its basis; manual meaning review remains required.
        var out = outcome(null, "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", "REVIEW_REQUIRED", "EXPRESSION_CONTENT", "상대에게 실제로 질문했다는 잘못 분류한 모의 주장");
        assertThat(SourceFirstJudgmentContract.assess(raw(), null, out).state()).isEqualTo("ASSESSMENT_RECORDED");
        assertThat(SourceFirstJudgmentContract.assess(raw(), null, out).publicationAuthorized()).isFalse();
    }
}
