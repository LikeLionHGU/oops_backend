package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CandidateRunOptionsTest {
    @Test void optionsRejectInvalidBudgetsAndUnsupportedFeatureCombinations() {
        var baseline=new VerificationFeatures(false,false,false);
        assertThatThrownBy(()->new RunOptions(0,0,false,baseline,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RunOptions(24,-1,false,baseline,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RunOptions(24,0,true,baseline,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RunOptions(24,0,false,baseline,false).evidenceLimit()).isZero();
    }
    @Test void recordEntryAndHistoricalEntrySendIdenticalDiscoveryAndVerificationRequests() {
        var f=new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"),f.proposal("stt-index-0","그 집"));
        f.verification(new Verification("candidate-1",f.assessment("stt-index-0","PASS","그 집")));
        var context=f.context("그 집 이용자는 수준이 낮아");
        var old=run(f.client,context,24,null,null,null,null,0);
        var current=run(f.client,context,new RunServices(null,null,null,null,null),
                new RunOptions(24,0,false,new VerificationFeatures(false,false,false),false));
        assertThat(current.findings()).hasSize(old.findings().size());
        var systems=ArgumentCaptor.forClass(String.class);
        var inputs=ArgumentCaptor.forClass(String.class);
        verify(f.client,times(2)).completeAsJson(systems.capture(),inputs.capture(),eq(Discovery.class));
        assertThat(systems.getAllValues().get(0)).isEqualTo(systems.getAllValues().get(1));
        assertThat(inputs.getAllValues().get(0)).isEqualTo(inputs.getAllValues().get(1));
        var verificationSystems=ArgumentCaptor.forClass(String.class);
        var verificationInputs=ArgumentCaptor.forClass(String.class);
        verify(f.client,times(2)).completeAsJson(verificationSystems.capture(),verificationInputs.capture(),eq(VerificationResult.class));
        assertThat(verificationSystems.getAllValues().get(0)).isEqualTo(verificationSystems.getAllValues().get(1));
        assertThat(verificationInputs.getAllValues().get(0)).isEqualTo(verificationInputs.getAllValues().get(1));
    }
}
