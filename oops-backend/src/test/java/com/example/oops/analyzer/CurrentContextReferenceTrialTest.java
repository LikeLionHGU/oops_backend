package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CurrentContextReferenceTrialTest {
    @Test void privatePreparationIsDeterministicAndContainsNoAnswerFields() throws Exception {
        var root=Path.of("../datasets/controversy");
        assumeTrue(Files.exists(root.resolve("context-units/mimiminu-mt-preference.json")));
        var plan=CurrentContextReferenceTrial.prepare(root);
        assertThat(ReviewGuidelineLibrary.JSON.writeValueAsString(CurrentContextReferenceTrial.prepare(root)))
                .isEqualTo(ReviewGuidelineLibrary.JSON.writeValueAsString(plan));
        assertThat(plan.reserved()).isLessThanOrEqualTo(1.0);
        for(var r:plan.requests()) {
            assertThat(r.input()).doesNotContain("expectedDecision","USER_APPROVED_DEVELOPMENT_BOUNDARY");
            assertThat(r.system()).startsWith(CandidateReviewEngine.verificationPrompt(0));
        }
        var extra=new ArrayList<>(plan.requests());extra.add(extra.get(0));
        assertThatThrownBy(()->CurrentContextReferenceTrial.validate(new CurrentContextReferenceTrial.Plan(
                plan.version(),plan.model(),plan.cap(),7,plan.hashes(),extra,plan.reserved()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CurrentContextReferenceTrial.validate(new CurrentContextReferenceTrial.Plan(
                plan.version(),plan.model(),plan.cap(),6,plan.hashes(),plan.requests(),1.01))).isInstanceOf(IllegalArgumentException.class);
    }
}
