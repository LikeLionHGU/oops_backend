package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.example.oops.analyzer.SourceFirstReviewBoundary.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Boundary/tamper tests only; no model judgment or truth of a reading is established. */
class SourceFirstReviewBoundaryTest {
    JsonNode source() { return new FinalReasonPremiseAuditTest().source(); }
    SourceRelationExtractionTrial.Observation observation() {
        return new SourceRelationExtractionTrial.Observation("b", "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE",
                List.of(new SourceRelationExtractionTrial.Link("a", "ANSWERS_MEANING_QUESTION")), List.of("a", "b"));
    }
    Snapshot snapshot(JsonNode input) { return freeze(input, observation(), "a".repeat(64)); }

    @Test void readerAndFreshJudgmentReceiveWhitelistedDataNotCandidateHistory() {
        var input = (ObjectNode) source();
        input.put("hypothesisNotEvidence", "HYPOTHESIS_SENTINEL"); input.put("expectedDecision", "LABEL_SENTINEL");
        input.put("judgmentToCheck", "REASON_SENTINEL"); input.put("contextReference", "DICTIONARY_SENTINEL");
        var reader = readingInput(input); var frozen = snapshot(input); var fresh = judgmentSource(input, frozen);
        assertThat(reader.propertyNames()).containsExactlyInAnyOrder("focusId", "segments");
        assertThat(fresh.propertyNames()).containsExactlyInAnyOrder("anchorId", "raw", "sourceReading");
        assertThat(reader.toString() + fresh).doesNotContain("SENTINEL", "hypothesisNotEvidence", "expectedDecision", "judgmentToCheck", "contextReference");
        assertThat(fresh.path("raw")).isEqualTo(input.path("raw"));
    }
    @Test void declarationThatPreservesReadingNeverAuthorizesPassOrPublication() {
        var source = source(); var frozen = snapshot(source);
        var binding = bind(source, frozen, new ReadingUse(frozen.readingSha256(), "DEFINITION_OR_EXPANSION", "MENTIONED_PHRASE", null, List.of()));
        assertThat(binding.state()).isEqualTo(State.CONSISTENT_DECLARATION);
        assertThat(binding.publicationAuthorized()).isFalse();
    }
    @Test void silentActReversalIsIncompleteAndExplicitChallengeStillRequiresRecheck() {
        var source = source(); var frozen = snapshot(source);
        assertThat(bind(source, frozen, new ReadingUse(frozen.readingSha256(), "DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT", null, List.of())).state())
                .isEqualTo(State.INCOMPLETE_BINDING);
        var explicit = bind(source, frozen, new ReadingUse(frozen.readingSha256(), "DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT",
                "원문 관계를 다시 확인해야 한다는 모의 이의", List.of("b")));
        assertThat(explicit.state()).isEqualTo(State.SOURCE_RECHECK_REQUIRED);
        assertThat(explicit.publicationAuthorized()).isFalse();
        assertThat(frozen.readingJson()).contains("DEFINITION_OR_EXPANSION");
    }
    @Test void sourceTextTimestampAndReadingTamperingAreRejected() {
        for (String field : List.of("text", "startMs")) {
            var source = source(); var frozen = snapshot(source); var line = (ObjectNode) source.path("raw").get(0);
            if (field.equals("text")) line.put(field, "변경 원문"); else line.put(field, 1);
            assertThatThrownBy(() -> verify(source, frozen)).hasMessageContaining("hash mismatch");
        }
        var source = source(); var frozen = snapshot(source);
        var tampered = new Snapshot(frozen.sourceInputSha256(), frozen.readingSha256(), "b".repeat(64), frozen.readingJson());
        assertThatThrownBy(() -> verify(source, tampered)).hasMessageContaining("hash mismatch");
    }
    @Test void outputEnvelopeMutationCannotMutateFrozenReadingAndForeignChallengeIdsFail() {
        var source = source(); var frozen = snapshot(source); var input = judgmentSource(source, frozen);
        ((ObjectNode) input.at("/sourceReading/observation")).put("function", "DIRECT_REQUEST_TO_LISTENER");
        verify(source, frozen);
        assertThat(frozen.readingJson()).contains("DEFINITION_OR_EXPANSION");
        assertThat(bind(source, frozen, new ReadingUse(frozen.readingSha256(), "DIRECT_REQUEST_TO_LISTENER", "CURRENT_ACT",
                "모의 이의", List.of("outside"))).state()).isEqualTo(State.INCOMPLETE_BINDING);
        assertThat(bind(source, frozen, null).state()).isEqualTo(State.INCOMPLETE_BINDING);
    }
    @Test void unclearOrUnsupportedReadingCannotSilentlyBecomeAnAcceptedDeclaration() {
        var source = source();
        var unclear = freeze(source, new SourceRelationExtractionTrial.Observation("b", "UNCLEAR", "UNCLEAR", List.of(), List.of("b")), "a".repeat(64));
        assertThat(bind(source, unclear, new ReadingUse(unclear.readingSha256(), "UNCLEAR", "UNCLEAR", null, List.of())).state())
                .isEqualTo(State.SOURCE_RECHECK_REQUIRED);
        var frozen = snapshot(source);
        assertThat(bind(source, frozen, new ReadingUse(frozen.readingSha256(), "UNKNOWN", "CURRENT_ACT", "모의 이의", List.of("b"))).detail())
                .isEqualTo("READING_USE_ENUM");
    }
}
