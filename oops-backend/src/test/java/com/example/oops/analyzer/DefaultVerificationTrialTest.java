package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class DefaultVerificationTrialTest {
    @Test void refusesOldConsentAndChangedPlanBeforeAnyKeyOrNetworkUse() throws Exception {
        assertThatThrownBy(() -> DefaultVerificationTrial.authorized(new byte[0],new byte[0],DefaultVerificationTrial.PLAN_SHA,
                HypothesisRemovalComparison.CONSENT)).hasMessageContaining("Fresh exact-plan");
        assertThatThrownBy(() -> DefaultVerificationTrial.authorized(new byte[0],new byte[0],DefaultVerificationTrial.PLAN_SHA,
                DefaultVerificationTrial.CONSENT)).hasMessageContaining("Fresh exact-plan");
    }
    @Test void blocksAlteredBodyExtraAttemptAndNonApprovedEndpoint() throws Exception {
        var p=new DefaultVerificationPlanTest().plan();var r=p.requests().get(0);
        var client=new OpenAiClient(RestClient.create(),new OpenAiProperties("offline",null,null,"https://api.openai.com/v1",
                p.model(),"none",Duration.ofSeconds(2),null));
        byte[] body=JSON.writeValueAsBytes(client.replayRequestBody(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class));
        var guard=new DefaultVerificationTrial.RequestGuard();guard.begin(body);
        var changed=(tools.jackson.databind.node.ObjectNode)JSON.readTree(body);changed.put("extra","not approved");
        assertThatThrownBy(() -> guard.accept(JSON.writeValueAsBytes(changed),DefaultVerificationTrial.ENDPOINT)).hasMessageContaining("frozen");
        assertThatThrownBy(() -> guard.accept(body,"https://example.com")).hasMessageContaining("endpoint");
        assertThat(guard.accept(body,DefaultVerificationTrial.ENDPOINT)).isNotEmpty();
        assertThatThrownBy(() -> guard.accept(body,DefaultVerificationTrial.ENDPOINT)).hasMessageContaining("extra HTTP");
    }
    @Test void cannotReuseSingleApprovedPlanEvenWithAnotherOutputDirectory(@TempDir Path dir) throws Exception {
        var file=dir.resolve("plan.json");Files.writeString(file,"synthetic fixture");
        DefaultVerificationTrial.claim(file,dir.resolve("first"));
        assertThatThrownBy(() -> DefaultVerificationTrial.claim(file,dir.resolve("second"))).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void offlineReplayPreservesLabelsDefaultFailuresAndExactRequestOrder() throws Exception {
        var plan=new DefaultVerificationPlanTest().plan();var saved=JSON.createObjectNode();
        saved.put("state","COMPLETE");saved.put("httpAttempts",56);saved.put("planSha256",DefaultVerificationTrial.PLAN_SHA);
        var rows=saved.putArray("results");
        for(var r:plan.requests()) {
            var response=new CandidateReviewEngine.VerificationResult(List.of());var row=rows.addObject();
            row.put("caseId",r.caseId());row.put("arm",r.arm());row.put("repeat",r.repeat());row.put("labelStatus",r.labelStatus());
            row.set("expectedDecision",JSON.valueToTree(r.expectedDecision()));
            row.set("assessment",JSON.valueToTree(DefaultVerificationPlan.assess(r,response)));
            var call=row.putArray("calls").addObject();call.put("kind","VerificationResult");call.put("system",r.system());
            call.set("input",JSON.readTree(r.input()));call.set("output",JSON.valueToTree(response));call.putNull("failureCode");
        }
        var report=DefaultVerificationTrial.audit(plan,saved);
        assertThat(report).containsEntry("providerCalls",0).containsEntry("historicalContractDrift",0);
        assertThat(JSON.valueToTree(report).at("/totals/KEPT:DRAFT_UNAPPROVED").has("approvedDecisionMatch")).isFalse();
        assertThat(JSON.valueToTree(report).at("/totals/KEPT:ALL/failure:TRIAL_RESPONSE_SHAPE").asInt()).isEqualTo(28);
        ((tools.jackson.databind.node.ObjectNode)rows.get(0).at("/calls/0/input")).put("changed",true);
        assertThatThrownBy(() -> DefaultVerificationTrial.audit(plan,saved)).hasMessageContaining("mismatch");
    }
}
