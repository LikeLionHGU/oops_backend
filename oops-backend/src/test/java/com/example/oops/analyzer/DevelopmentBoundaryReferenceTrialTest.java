package com.example.oops.analyzer;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Synthetic execution guards only; never invokes a provider. */
class DevelopmentBoundaryReferenceTrialTest {
    @TempDir Path temp;
    @Test void sixAttemptLimitAndReservationCannotBeExceeded() throws Exception {
        var budget=new TextBoundaryTrial.Budget(.25,.10,.50,temp);
        for(int i=0;i<6;i++) DevelopmentBoundaryReferenceTrial.reserve(budget,1000);
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.reserve(budget,1000)).hasMessageContaining("Six");
        assertThat(budget.attempts).isEqualTo(6);
        var small=new TextBoundaryTrial.Budget(.001,.10,.50,temp);
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.reserve(small,1000)).hasMessageContaining("exhausted");
        assertThat(small.attempts).isZero();
    }
    DevelopmentBoundaryReferenceTrial.Plan plan(boolean changeInput) {
        var rows=new ArrayList<DefaultVerificationPlan.Request>();
        String input="{\"raw\":[{\"id\":\"s1\",\"text\":\"합성 대사\",\"startMs\":0,\"endMs\":1000}],\"candidates\":[{}]}";
        for(int r=1;r<=3;r++) for(String arm:List.of("BEFORE","AFTER"))
            rows.add(new DefaultVerificationPlan.Request("synthetic","DRAFT","REVIEW_REQUIRED",arm,r,
                    CandidateReviewEngine.verificationPrompt(0)+arm,changeInput && arm.equals("AFTER")?input.replace("합성 대사","다른 대사"):input));
        return new DevelopmentBoundaryReferenceTrial.Plan(DevelopmentBoundaryReferenceTrial.VERSION,"gpt-6-luna",.25,6,
                "source","runtime",Map.of(),rows,.03);
    }
    @Test void armsMustKeepExactlyTheSameInputAndPromptPrefix() {
        DevelopmentBoundaryReferenceTrial.validate(plan(false));
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.validate(plan(true))).hasMessageContaining("Only references");
    }
}
