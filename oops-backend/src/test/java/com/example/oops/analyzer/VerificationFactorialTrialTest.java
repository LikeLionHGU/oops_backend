package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.VerificationFactorialPlan.*;
import static com.example.oops.analyzer.VerificationFactorialTrial.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No real provider calls; mocked transport results never establish semantic correctness. */
class VerificationFactorialTrialTest {
    @TempDir Path temporary;
    Plan plan() throws Exception {
        var i=new VerificationFactorialPlanTest().inputs();return prepare(i.source(),i.human(),i.mappings());
    }
    @Test void authorizationCannotReusePreviousConsentOrChangedHash() throws Exception {
        byte[] bytes=JSON.writeValueAsBytes(plan());String sha=ReferenceOnlyTrial.sha(bytes);
        assertThat(authorize(bytes,sha,CONSENT).maxRequests()).isEqualTo(112);
        assertThatThrownBy(() -> authorize(bytes,sha,UtteranceDirectionTrial.BASIS_CONSENT)).hasMessageContaining("fresh USD1");
        assertThatThrownBy(() -> authorize(bytes,"0".repeat(64),CONSENT)).hasMessageContaining("Frozen plan");
    }
    @Test void validatesExactRegenerationBeforeAnyProviderConstruction() throws Exception {
        var i=new VerificationFactorialPlanTest().inputs();var p=prepare(i.source(),i.human(),i.mappings());
        validate(p,i.source(),i.human(),i.mappings());
        var changed=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        ((tools.jackson.databind.node.ObjectNode)changed.path("requests").get(0)).put("input","{\"raw\":[]}");
        assertThatThrownBy(() -> validate(JSON.treeToValue(changed,Plan.class),i.source(),i.human(),i.mappings()))
                .hasMessageContaining("differs from frozen");
    }
    @Test void schedulesEachOfFiftySixRequestsTwiceWithReversedSecondOrder() throws Exception {
        var slots=schedule(plan());assertThat(slots).hasSize(112);
        assertThat(slots.subList(0,4).stream().map(s -> s.request().arm()).toList()).containsExactly(Arm.values());
        assertThat(slots.subList(56,60).stream().map(s -> s.request().arm()).toList()).containsExactly(Arm.BOTH,Arm.CONTRACT_ONLY,Arm.GUARD_ONLY,Arm.BASELINE);
        assertThat(slots.stream().map(s -> s.repeat()+"/"+s.request().caseId()+"/"+s.request().arm()).distinct().count()).isEqualTo(112);
    }
    @Test void includesRetriesInAttemptCapAndPersistsBeforeSend() throws Exception {
        var budget=new Budget(temporary);
        for(int i=0;i<112;i++) budget.reserve(0);
        String before=Files.readString(temporary.resolve("reservation.json"));
        assertThat(JSON.readTree(before).path("maxHttpAttempts").asInt()).isEqualTo(112);
        assertThatThrownBy(() -> budget.reserve(0)).hasMessageContaining("112 HTTP attempt cap");
        assertThat(budget.attempts).isEqualTo(112);assertThat(Files.readString(temporary.resolve("reservation.json"))).isEqualTo(before);
    }
    @Test void costLimitOrReservationWriteFailurePreventsSending() {
        var expensive=new Budget(temporary);
        assertThatThrownBy(() -> expensive.reserve(10_000_000)).hasMessageContaining("Fresh budget");
        assertThat(expensive.attempts).isZero();assertThat(expensive.reserved).isZero();
        var unwritable=new Budget(temporary.resolve("missing-directory"));
        assertThatThrownBy(() -> unwritable.reserve(0)).isInstanceOf(java.io.IOException.class);
        assertThat(unwritable.attempts).isZero();assertThat(unwritable.reserved).isZero();
    }
    @Test void unlabeledDraftsAndRejectedWarningsCannotBecomeCorrectPasses() {
        var d=new CandidateReviewEngineTest().assessment("x","REVIEW_REQUIRED","합성 원문");
        var response=new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification("c",d)));
        var draft=new Request("normal",Arm.BASELINE,"DRAFT",null,"system","{}");
        assertThat(score(draft,response,Map.of("accepted",true))).containsEntry("decisionMatchesUserBoundary",null).containsEntry("acceptedMatchingDecision",null);
        var pass=new Request("r",Arm.CONTRACT_ONLY,"USER","PASS","system","{}");
        assertThat(score(pass,response,Map.of("accepted",false))).containsEntry("returnedDecision","REVIEW_REQUIRED")
                .containsEntry("acceptedMatchingDecision",false);
        assertThat(score(pass,null,Map.of("accepted",false))).containsEntry("returnedDecision",null).containsEntry("acceptedMatchingDecision",false);
    }
    MechanismRoutingTrial.Client offlineClient() {
        var props=new OpenAiProperties("offline-test-key",null,null,"https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(1),
                new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        return spy(new MechanismRoutingTrial.Client(RestClient.create(),props));
    }
    @Test void providerFailurePreservesPartialRowAndStopsAfterOneMockedCall() throws Exception {
        var p=plan();var client=offlineClient();var budget=new Budget(temporary);
        doReturn(Optional.empty()).when(client).completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.VerificationResult.class));
        doReturn(1).when(client).failureCount();
        assertThatThrownBy(() -> execute(p,"test-sha",client,budget,temporary)).hasMessageContaining("Provider/usage failure");
        verify(client,times(1)).completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.VerificationResult.class));
        var saved=JSON.readTree(Files.readString(temporary.resolve("results.json")));
        assertThat(saved.path("state").asText()).isEqualTo("STOPPED");assertThat(saved.path("results").size()).isEqualTo(1);
        assertThat(saved.at("/results/0/assessment/accepted").asBoolean()).isFalse();
    }
    @Test void fullFreeRehearsalPreservesFrozenRequestsAndCompletesAccounting() throws Exception {
        var p=plan();var client=offlineClient();var budget=new Budget(temporary);var slots=schedule(p);var position=new int[]{0};
        var pricing=new OpenAiProperties.Pricing(.10,.01,.50,null,null);
        doReturn(new OpenAiClient.TokenUsage(1,1,0,1,"gpt-6-luna",pricing)).when(client).videoUsage();
        doAnswer(call -> {
            var expected=slots.get(position[0]++).request();
            assertThat((String)call.getArgument(0)).isEqualTo(expected.system());assertThat((String)call.getArgument(1)).isEqualTo(expected.input());
            budget.reserve(0);client.calls.add(Map.of("kind","MOCK_VERIFICATION"));
            return Optional.of(new CandidateReviewEngine.VerificationResult(List.of()));
        }).when(client).completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.VerificationResult.class));
        execute(p,"mock-sha",client,budget,temporary);
        var saved=JSON.readTree(Files.readString(temporary.resolve("results.json")));
        assertThat(saved.path("state").asText()).isEqualTo("COMPLETE");assertThat(saved.path("httpAttempts").asInt()).isEqualTo(112);
        assertThat(saved.path("results").size()).isEqualTo(112);
        saved.path("results").forEach(row -> assertThat(row.at("/assessment/accepted").asBoolean()).isFalse());
        // Empty mock responses validate orchestration/accounting only, never a claim of 112 correct judgments.
    }
}
