package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepairPromptContractTest {
    @Test void discoveryRepairReturnsIdsOnlyWithoutQuoteCopying() {
        assertThat(DISCOVERY_REPAIR_PROMPT).contains("quote는 넣지 않는다", "서버가 선택한 segmentId의 원문을 채운다")
                .doesNotContain("\"quote\"", "quote는 한 허용 창의 실제 원문만 복사");
    }
    @Test void finiteLimitsRenderBothRepairCountsAndUnlimitedCannotOverflow() {
        for (int limit : List.of(1,3,8,12)) {
            assertThat(repairPrompt("EVIDENCE_BUDGET_EXCEEDED",limit))
                    .contains("객체 수가 " + limit + "개", "합쳐 최대 " + limit + "개", "segmentId의 raw.text")
                    .doesNotContain("TARGET.quote", "{{");
        }
        assertThatThrownBy(()->repairPrompt("EVIDENCE_BUDGET_EXCEEDED",0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repairPrompt("TARGET_REQUIRED",0)).isEqualTo(TARGET_REPAIR_PROMPT);
    }
    @Test void absentOrDuplicateVerificationMarkerFailsInsteadOfSilentlyKeepingOldLimit() {
        String marker="evidence는 최대 8개로 허용 원문의 segmentId·role을 반환한다.";
        assertThatThrownBy(()->verificationPrompt("changed contract",0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->verificationPrompt(marker+marker,3)).isInstanceOf(IllegalStateException.class);
        assertThat(verificationPrompt(8)).isEqualTo(VERIFICATION_PROMPT);
        assertThat(verificationPrompt(0)).contains("고정 개수 상한은 없다").doesNotContain(marker);
    }
    @Test void actualRepairRequestUsesConfiguredLimitRatherThanCompatibilityDefaultEight() {
        var f=new CandidateReviewEngineTest();
        var good=f.assessment("stt-index-0","REVIEW_REQUIRED","그 집");
        var evidence=new ArrayList<>(good.evidence());
        while(evidence.size()<4) evidence.add(new TextReviewEngine.LlmEvidence("stt-index-0","그 집","CONTEXT"));
        var overflow=new TextReviewEngine.LlmDecision(good.segmentId(),good.decision(),good.evidenceText(),good.reason(),good.category(),good.target(),good.score(),
                good.context(),good.reading(),good.missingInformation(),evidence,good.targetType(),good.targetRelation(),good.targetReason(),good.alternativeInterpretation(),good.targetMention());
        f.discovery(List.of("stt-index-0"),f.proposal("stt-index-0","그 집"));
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1",overflow)))))
                .thenAnswer(call->{
                    assertThat((String)call.getArgument(0)).contains("합쳐 최대 3개", "객체 수가 3개").doesNotContain("TARGET.quote", "객체 수가 8개");
                    return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",f.assessment("stt-index-0","PASS","그 집")))));
                });
        var result=run(f.client,f.context("그 집 이용자는 수준이 낮아"),24,null,null,null,null,3);
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
        assertThat(result.findings()).isEmpty();
    }
}
