package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SyntheticReferenceRegressionTest {
    @Test void repairInstructionsAfterReferenceArePreserved(){
        String original="prefix contextReference={\"x\":1}\nREPAIR_INSTRUCTIONS";
        assertThatThrownBy(()->ConfrontationStageTrial.system(original)).isInstanceOf(RuntimeException.class);
        assertThat(SyntheticReferenceComparison.trialSystem(original,false)).isEqualTo(original);
        assertThat(SyntheticReferenceComparison.trialSystem(original,true)).isEqualTo(original+SyntheticReferenceComparison.ADDITION);
        String nested="prefix contextReference={\"z\":\"brace } and escaped \\\"quote\\\"\",\"a\":[1]}\nREPAIR_INSTRUCTIONS";
        assertThat(SyntheticReferenceComparison.trialSystem(nested,false)).endsWith("\nREPAIR_INSTRUCTIONS")
                .contains("\"a\":[1],\"z\":");
    }
    @Test void partialAnalysisPermanentlyStopsTheTrial(){
        var guard=new SyntheticReferenceRegression.TrialGuard();
        assertThat(SyntheticReferenceRegression.acceptResult(guard,"PARTIAL",0)).isFalse();
        assertThat(guard.blocked).isTrue();
        guard.beginCase();
        assertThatThrownBy(()->guard.begin("invalid".getBytes())).hasMessageContaining("blocked");
    }
    @Test void invalidRequestPermanentlyStopsTheTrial(){
        var guard=new SyntheticReferenceRegression.TrialGuard();
        assertThatThrownBy(()->guard.accept("invalid".getBytes(),"https://invalid.example"));
        assertThat(guard.blocked).isTrue();
        guard.beginCase();
        assertThatThrownBy(()->guard.begin("invalid".getBytes())).hasMessageContaining("blocked");
        assertThat(guard.attempts).isZero();
    }
}
