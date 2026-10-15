package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline assembly tests, not model judgments and not new approved labels. */
class ContextJudgmentPolicyTest {
    @Test void replacesOnlyPolicyAndPreservesTheFullOutputContractAndFrozenReferences() {
        for (String baseline:List.of(SourceRelationPrompts.DISCOVERY,
                SourceRelationPrompts.verification(0),SourceRelationPrompts.verification(8),
                CandidateReviewPrompts.DISCOVERY_PROMPT,CandidateReviewPrompts.VERIFICATION_PROMPT)) {
            String frozen=baseline+"\ncontextReference={\"guidelines\":[\"test-only\"]}";
            var pair=ContextJudgmentPolicy.pair(frozen,"{\"raw\":[\"same-input\"]}");
            assertThat(pair.baselineSystem()).isEqualTo(frozen);
            assertThat(pair.comparisonSystem().substring(ContextJudgmentPolicy.POLICY.length()))
                    .isEqualTo(frozen.substring(CandidateReviewPrompts.POLICY.length()));
            assertThat(pair.input()).isEqualTo("{\"raw\":[\"same-input\"]}");
        }
    }
    @Test void refusesUnknownPrefixesAndDoesNotAccumulatePolicyAddenda() {
        assertThatThrownBy(()->ContextJudgmentPolicy.replacePolicy("unknown system"))
                .isInstanceOf(IllegalArgumentException.class);
        String once=ContextJudgmentPolicy.replacePolicy(SourceRelationPrompts.DISCOVERY);
        assertThatThrownBy(()->ContextJudgmentPolicy.replacePolicy(once)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ContextJudgmentPolicy.pair(SourceRelationPrompts.DISCOVERY," "))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualDiscoveryAndVerificationRequestsCanBePairedWithoutChangingRawOrRoles() {
        // Synthetic attribution/banter/reporting probes. No labels are asserted here.
        for (List<String> speech:List.of(
                List.of("그 배우와 닮았다는 이야기였어요","제가 언제 사칭했어요","뻔뻔하네"),
                List.of("저는 집에서는 씻지 않아요","내 몸에서 나오는 것은 깨끗하다고 생각해요","그래서 그렇게 말했어요"),
                List.of("가해자가 피해자를 도구처럼 취급했다고 합니다","그런 행위와 조직의 대응을 비판합니다","피해자 보호가 필요합니다"))) {
            var f=new CandidateReviewEngineTest();var context=f.context(speech.toArray(String[]::new));
            var ids=List.of("stt-index-0","stt-index-1","stt-index-2");
            var roles=new SourceRelations(List.of(ids.get(0)),List.of(),List.of(ids.get(1)),List.of(ids.get(2)),List.of());
            f.discovery(ids,new Proposal(ids.get(2),"TARGET_TREATMENT",null,
                    ids.stream().map(id->new Quote(id,null)).toList(),roles));
            f.verification(new Verification("candidate-1",f.linkedPass(ids.get(2),speech.get(2),ids.get(0),speech.get(0))));
            var result=run(f.client,context,24,null,null,null,null,0,true);
            var systems=ArgumentCaptor.forClass(String.class);var inputs=ArgumentCaptor.forClass(String.class);
            verify(f.client,times(2)).completeAsJson(systems.capture(),inputs.capture(),any());
            for (int i=0;i<2;i++) {
                var pair=ContextJudgmentPolicy.pair(systems.getAllValues().get(i),inputs.getAllValues().get(i));
                assertThat(pair.input()).isEqualTo(inputs.getAllValues().get(i));
                assertThat(pair.comparisonSystem()).startsWith(ContextJudgmentPolicy.POLICY);
            }
            var request=JSON.readTree(inputs.getAllValues().get(1));
            assertThat(request.get("candidates").get(0).get("sourceRelations")).isEqualTo(JSON.valueToTree(roles));
            assertThat(request.get("raw").toString()).contains(speech.get(0),speech.get(1),speech.get(2));
            assertThat(result.diagnostics().candidatePipeline().revision()).isEqualTo(RELATION_REVISION);
            assertThat(result.findings()).isEmpty(); // Mock PASS, not evidence of improved specificity.
        }
    }
    @Test void comparisonRetainsPositiveMechanismsWithoutBlanketBanterOrReportingExemptions() {
        assertThat(ContextJudgmentPolicy.POLICY).contains("농담·비판적 인용도 자동 면책은 아니다",
                "주민 전체 공격을 필수로 요구하지 않는다", "실제 폭력 의도나 피해자·공격 대상을 요구하지 않는다",
                "서술된 가해 행위를 화자의 행위로 돌리지 말고", "같은 표현에 실제 모욕이 있을 수 있지만");
        assertThat(ContextJudgmentPolicy.POLICY).doesNotContain("피식대학","롯데리아","김잔디");
    }
    private tools.jackson.databind.node.ObjectNode savedTrace() throws Exception {
        String system=SourceRelationPrompts.verification(0)+"\ncontextReference={\"same\":true}";
        String input=JSON.writeValueAsString(Map.of("promptRevision",RELATION_REVISION,
                "raw",List.of(Map.of("id","s","text","원문")),
                "candidates",List.of(Map.of("candidateId","c1","anchorId","s"))));
        return JSON.valueToTree(Map.of("videoId",66,"scope","TEXT_INITIAL_VERIFICATION_ONLY_PARSED_RESPONSE",
                "droppedRequests",0,"truncated",false,"requests",List.of(Map.of("system",system,"input",input,
                        "systemSha256",PrepareContextJudgmentComparison.sha(system),
                        "inputSha256",PrepareContextJudgmentComparison.sha(input)))));
    }
    @Test void offlinePreparationPreservesFrozenInputAndRejectsPartialOrTamperedTraces() throws Exception {
        var source=savedTrace();
        var prepared=JSON.valueToTree(PrepareContextJudgmentComparison.prepare(source));
        assertThat(prepared.path("providerCalls").asInt()).isZero();
        assertThat(prepared.path("labelsIncluded").asBoolean()).isFalse();
        assertThat(prepared.path("sourceVideoId").asLong()).isEqualTo(66);
        assertThat(prepared.path("pairs").get(0).get("input"))
                .isEqualTo(source.path("requests").get(0).get("input"));
        assertThat(PrepareContextJudgmentComparison.prepare(JSON.valueToTree(Map.of("data",Map.of("snapshot",source)))))
                .isEqualTo(PrepareContextJudgmentComparison.prepare(source));
        var partial=savedTrace();partial.put("truncated",true);
        assertThatThrownBy(()->PrepareContextJudgmentComparison.prepare(partial)).isInstanceOf(IllegalArgumentException.class);
        var invalidCount=savedTrace();invalidCount.put("droppedRequests","unknown");
        assertThatThrownBy(()->PrepareContextJudgmentComparison.prepare(invalidCount)).isInstanceOf(IllegalArgumentException.class);
        var tampered=savedTrace();((tools.jackson.databind.node.ObjectNode)tampered.path("requests").get(0)).put("input","{}");
        assertThatThrownBy(()->PrepareContextJudgmentComparison.prepare(tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PrepareContextJudgmentComparison.prepare(JSON.createObjectNode())).isInstanceOf(IllegalArgumentException.class);
    }
}
