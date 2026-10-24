package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UncertaintyDiagnosticTest {
    @Test void labelsSignalsWithoutCertifyingNecessityOrChangingDecision() {
        var d = UncertaintyDiagnostic.from(List.of("전사가 불명확하며 앞뒤 문맥과 화자·원음 확인이 필요하다"));
        assertThat(d.signals()).containsExactly("TRANSCRIPT_QUALITY", "CONTEXT_REQUEST", "SPEAKER_OR_DELIVERY_REQUEST");
        assertThat(d.necessityValidated()).isFalse();
        assertThat(UncertaintyDiagnostic.from(List.of("해석이 어렵다")).signals()).containsExactly("UNCLASSIFIED");
        assertThat(UncertaintyDiagnostic.from(List.of("stt-index-5의 불명확한 발화가 실제로 무엇을 뜻하는지")).signals())
                .containsExactly("TRANSCRIPT_QUALITY");
    }
    @Test void serializedDecisionAddsOnlyDiagnosticMetadata() {
        var original = new ReviewDiagnostics.DecisionTrace(ReviewEvaluation.Decision.UNCERTAIN,
                "판단 보류", null,null,List.of("화자 확인 필요"),List.of(),false);
        String json = ReviewGuidelineLibrary.JSON.writeValueAsString(original);
        assertThat(json).contains("uncertaintyDiagnostic", "SPEAKER_OR_DELIVERY_REQUEST", "UNCERTAIN", "화자 확인 필요");
        var pass = new ReviewDiagnostics.DecisionTrace(ReviewEvaluation.Decision.PASS,"통과",null,null,List.of(),List.of(),false);
        assertThat(ReviewGuidelineLibrary.JSON.writeValueAsString(pass)).doesNotContain("uncertaintyDiagnostic");
    }
}
