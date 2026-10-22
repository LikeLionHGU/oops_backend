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
    @Test void normalComparisonStopsBeforeSeventeenthAttempt() throws Exception {
        var budget=new TextBoundaryTrial.Budget(.25,.10,.50,temp);
        for(int i=0;i<16;i++) DevelopmentBoundaryReferenceTrial.reserve(budget,1000,16);
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.reserve(budget,1000,16)).hasMessageContaining("Sixteen");
        assertThat(budget.attempts).isEqualTo(16);
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.reserve(budget,1000,17)).hasMessageContaining("approved request cap");
    }
    DevelopmentBoundaryReferenceTrial.Plan normalPlan(boolean mutate) {
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        var raw=ReviewGuidelineLibrary.JSON.readTree("[{\"id\":\"s1\",\"text\":\"합성 대사\",\"startMs\":0,\"endMs\":1000}]");
        for(String id:List.of("product-duration-guidance","study-restart-encouragement","actual-audience-review","actual-victim-quotation")) {
            var input=DevelopmentBoundaryReferenceTrial.normalInput(new DevelopmentBoundaryReferenceTrial.Boundary(id,
                    id.equals("actual-audience-review")?"REVIEW_REQUIRED":"PASS","s1",raw,false));
            for(int repeat=1;repeat<=2;repeat++) for(String arm:List.of("BEFORE","AFTER"))
                requests.add(new DefaultVerificationPlan.Request(id,"USER_APPROVED_DEVELOPMENT_BOUNDARY",
                        id.equals("actual-audience-review")?"REVIEW_REQUIRED":"PASS",arm,repeat,
                        CandidateReviewEngine.verificationPrompt(0)+arm,
                        mutate && arm.equals("AFTER")?input.replace("합성 대사","다른 대사"):input));
        }
        return new DevelopmentBoundaryReferenceTrial.Plan(DevelopmentBoundaryReferenceTrial.NORMAL_VERSION,"gpt-6-luna",.25,16,
                "source","runtime",Map.of(),requests,.10);
    }
    @Test void normalComparisonRequiresPairedFrozenInputsAndAllFourApprovedConditions() {
        DevelopmentBoundaryReferenceTrial.validate(normalPlan(false));
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.validate(normalPlan(true))).hasMessageContaining("Only references");
    }
    @Test void pendingLabelsCannotBecomeExecutablePlan() {
        assertThatThrownBy(()->DevelopmentBoundaryReferenceTrial.normalPlan(
                "{\"status\":\"PENDING_NORMAL_LABEL_APPROVAL\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[0],new byte[0],temp)).hasMessageContaining("approval required");
    }
}
