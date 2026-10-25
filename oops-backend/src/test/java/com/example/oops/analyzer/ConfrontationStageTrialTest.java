package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConfrontationStageTrialTest {
    private byte[] request() {
        var client=new com.example.oops.client.OpenAiClient(RestClient.create(),ConfrontationStageTrial.props("offline"),true);
        return JSON.writeValueAsBytes(client.replayRequestBody("fixed system","{\"raw\":[]}",CandidateReviewEngine.VerificationResult.class));
    }
    @Test void guardBoundsOutputModelEndpointAndBlocksRetries() {
        var guard=new ConfrontationStageTrial.Guard();guard.beginCase();guard.begin(request());
        var body=JSON.readTree(guard.accept(request(),ConfrontationStageTrial.ENDPOINT));
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(8000);
        assertThat(guard.attempts).isOne();assertThat(guard.reserved).isGreaterThan(0).isLessThan(.50);
        assertThatThrownBy(()->guard.accept(request(),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ConfrontationStageTrial.body(request(),"https://example.invalid")).isInstanceOf(IllegalArgumentException.class);
        var altered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(request());altered.put("model","other-model");
        assertThatThrownBy(()->ConfrontationStageTrial.body(JSON.writeValueAsBytes(altered),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void guardRejectsOversizeAndLabelLeakage() {
        var altered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(request());
        ((tools.jackson.databind.node.ObjectNode)altered.path("messages").get(1)).put("content","{\"expectedDecision\":\"PASS\"}");
        assertThatThrownBy(()->ConfrontationStageTrial.body(JSON.writeValueAsBytes(altered),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
        ((tools.jackson.databind.node.ObjectNode)altered.path("messages").get(0)).put("content","x".repeat(120001));
        assertThatThrownBy(()->ConfrontationStageTrial.body(JSON.writeValueAsBytes(altered),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void canonicalizationDoesNotChangeDataAndKeepsDifferentObjectOrderStable() {
        assertThat(ConfrontationStageTrial.canonical("{\"b\":2,\"a\":[{\"y\":1,\"x\":0}]}"))
                .isEqualTo(ConfrontationStageTrial.canonical("{\"a\":[{\"x\":0,\"y\":1}],\"b\":2}"));
        String original="정책 원문\ncontextReference={\"z\":1,\"a\":\"한글 😀\"}";
        String normalized=ConfrontationStageTrial.system(original);
        assertThat(normalized).startsWith("정책 원문\ncontextReference=").contains("한글 😀");
        assertThat(JSON.readTree(normalized.substring(normalized.indexOf("contextReference=")+17)))
                .isEqualTo(JSON.readTree("{\"z\":1,\"a\":\"한글 😀\"}"));
    }
    @Test void seededDiscoveryUsesNoTransportAndDoesNotReturnAFakeReviewVerdict() {
        var raw=List.of(new ReviewInput.Segment("s",com.example.oops.domain.TimelineEventType.SPEECH,0,1000,"단순 회고",null));
        var c=new ConfrontationStageTrial.Condition("control","SYNTHETIC","PASS",raw,
                new ConfrontationStageTrial.Seed("s","EXPRESSION_CONTENT","전달 방식 대조",List.of("s")));
        var builder=RestClient.builder().baseUrl("https://example.invalid");
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client=new ConfrontationStageTrial.Client(builder.build(),c,"SEEDED_DIAGNOSTIC",new ConfrontationStageTrial.Guard(),false,List.of(),"offline");
        var response=client.completeAsJson("unused","{}",CandidateReviewEngine.Discovery.class).orElseThrow();
        assertThat(response.candidates()).hasSize(1);assertThat(client.calls).isEmpty();assertThat(client.captured).isEmpty();
        server.verify();
    }
    @Test void perModeAndOverallCapsStopBeforeExtraHttp() {
        var guard=new ConfrontationStageTrial.Guard();guard.beginCase();
        for(int i=0;i<4;i++){guard.begin(request());guard.accept(request(),ConfrontationStageTrial.ENDPOINT);}
        guard.begin(request());
        assertThatThrownBy(()->guard.accept(request(),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.attempts).isEqualTo(4);assertThat(guard.blocked).isTrue();
        var total=new ConfrontationStageTrial.Guard();
        for(int group=0;group<6;group++){total.beginCase();for(int i=0;i<4;i++){total.begin(request());total.accept(request(),ConfrontationStageTrial.ENDPOINT);}}
        total.beginCase();total.begin(request());
        assertThatThrownBy(()->total.accept(request(),ConfrontationStageTrial.ENDPOINT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(total.attempts).isEqualTo(24);
        assertThat(24*ConfrontationStageTrial.reservation(120000)).isCloseTo(.3864576,within(.00000001)).isLessThan(.50);
    }
    @Test void refusesSeedOutsideRawAndPreservesCurrentText() {
        var raw=List.of(new ReviewInput.Segment("s",com.example.oops.domain.TimelineEventType.SPEECH,0,1000,"현재 원문",null));
        var invalid=new ConfrontationStageTrial.Condition("case","SYNTHETIC","PASS",raw,
                new ConfrontationStageTrial.Seed("outside","EXPRESSION_CONTENT","가설",List.of("outside")));
        assertThatThrownBy(()->ConfrontationStageTrial.validate(invalid)).isInstanceOf(IllegalArgumentException.class);
        var valid=new ConfrontationStageTrial.Condition("case","SYNTHETIC","PASS",raw,
                new ConfrontationStageTrial.Seed("s","EXPRESSION_CONTENT","가설",List.of("s")));
        ConfrontationStageTrial.validate(valid);
        assertThat(ConfrontationStageTrial.seed(valid).candidates().get(0).evidence().get(0).quote()).isEqualTo("현재 원문");
    }
    @Test void privateFrozenPreparationBuildsRequestsOrRejectsAnOutdatedRuntimePin() throws Exception {
        var source=Path.of("../datasets/controversy/experiments/confrontation-stage-plan-20261025/plan.json");
        assumeTrue(Files.exists(source));
        try {
            var plan=ConfrontationStageTrial.prepare(source);
            assertThat(plan.cases()).hasSize(3);assertThat(plan.requests()).hasSize(6);
            assertThat(plan.worstReservedUsd()).isLessThan(.50);
            for(var r:plan.requests()) {
                var input=JSON.readTree(r.input());assertThat(input.has("expectedDecision")).isFalse();
                assertThat(input.has("developerProposal")).isFalse();
                assertThat(r.kind()).isEqualTo(r.mode().equals("AUTO")?"Discovery":"VerificationResult");
                if(r.caseId().equals("actual-confrontation"))assertThat(input.path("raw").size()).isEqualTo(58);
            }
        } catch (IllegalArgumentException stale) {
            // Frozen plans must not be rewritten when runtime metadata changes; they require a fresh review.
            assertThat(stale).hasMessageContaining("Runtime drift");
            assertThatThrownBy(() -> ConfrontationStageTrial.prepare(source)).hasMessageContaining("Runtime drift");
        }
    }
    @Test void executablePlanRejectsWrongApprovalHashBeforeAnyCredentialOrNetworkUse() throws Exception {
        var frozen=Path.of("../datasets/controversy/experiments/confrontation-stage-executable-20261025/plan.json");
        assumeTrue(Files.exists(frozen));
        var output=frozen.toAbsolutePath().getParent().getParent().resolve("never-created-"+UUID.randomUUID());
        assertThatThrownBy(()->ConfrontationStageTrial.main(new String[]{"run",frozen.toString(),"wrong-hash",output.toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fresh exact-plan");
        assertThat(Files.exists(output)).isFalse();
    }
}
