package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Preparation only. No key loading, provider execution, historical result reclassification or promotion. */
public final class DefaultVerificationPlan {
    static final String VERSION="default-verification-hypothesis-plan-20261017-1";
    static final String SOURCE_SHA="feec9ff9a8413e8dc26aeb65ce38ba0e2c6d01616bf08775908b165faccd6112";
    static final String REFERENCE_BOUNDARY="# 범용 맥락 참고 기준\n";
    record Request(String caseId,String labelStatus,String expectedDecision,String arm,int repeat,String system,String input) {}
    record Plan(String schemaVersion,String model,String engineRevision,String sourcePlanSha256,String historicalRuntimeSha256,
                String scope,boolean independentGold,boolean freshApprovalRequired,boolean executionImplemented,
                int maxHttpAttempts,double proposedEstimatedCapUsd,double reservationEstimatedUsd,List<Request> requests) {}
    private DefaultVerificationPlan() {}

    static String reference(String frozenSystem) {
        int start=frozenSystem.indexOf(REFERENCE_BOUNDARY);
        check(start>=0 && start==frozenSystem.lastIndexOf(REFERENCE_BOUNDARY),"One frozen reference boundary required");
        String suffix=frozenSystem.substring(start);
        check(suffix.contains("contextReference="),"Frozen dictionary payload required");
        return suffix;
    }
    static Plan plan(UtteranceDirectionTrial.Plan source,String sha) throws Exception {
        UtteranceDirectionTrial.validate(source);
        check(UtteranceDirectionTrial.removal(source),"Frozen hypothesis study required");
        var requests=new ArrayList<Request>();
        for(int repeat=1;repeat<=2;repeat++) for(var t:source.tasks()) {
            String system=CandidateReviewEngine.verificationSystem(CandidateReviewEngine.verificationPrompt(0),
                    new CandidateReviewEngine.VerificationFeatures(false,false,false))+"\n"+reference(t.baselineSystem());
            var input=(tools.jackson.databind.node.ObjectNode)JSON.readTree(t.input());
            check(!input.at("/candidates/0").has("sourceRelations"),"Default discovery candidate required");
            input.put("promptRevision",CandidateReviewEngine.REVISION);
            String kept=input.toString(),removed=HypothesisRemovalComparison.input(kept);
            for(String arm:repeat==1?List.of("KEPT","REMOVED"):List.of("REMOVED","KEPT"))
                requests.add(new Request(t.id(),t.labelStatus(),t.expectedDecision(),arm,repeat,system,arm.equals("KEPT")?kept:removed));
        }
        check(requests.size()==56,"Fixed 14 conditions, two arms and two repeats required");
        double reservation=reservation(requests,source.model());
        check(reservation<=.50,"Proposed reservation exceeds cap; fresh plan required");
        return new Plan(VERSION,source.model(),CandidateReviewEngine.REVISION,sha,source.runtimeSha256(),
                "FIXED_CANDIDATE_VERIFICATION_ONLY_FROZEN_REFERENCES_NOT_FULL_PIPELINE",false,true,false,
                56,.50,reservation,List.copyOf(requests));
    }
    static double reservation(List<Request> requests,String model) {
        // Repository-configured estimate, not a current price quotation or billing guarantee.
        var client=new OpenAiClient(RestClient.create(),new OpenAiProperties("offline-test-key",null,null,
                "https://api.openai.com/v1",model,"none",Duration.ofSeconds(2),null));
        double sum=0;
        for(var request:requests) {
            byte[] body=JSON.writeValueAsBytes(client.replayRequestBody(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class));
            byte[] guarded=V7RoutingVideoTrial.guardBody(body,"https://api.openai.com/v1/chat/completions");
            sum+=(guarded.length+1024)*.10/1_000_000+8000*.50/1_000_000;
        }
        return sum;
    }
    static Map<String,Object> assess(Request r,CandidateReviewEngine.VerificationResult response) {
        var task=new UtteranceDirectionTrial.Task(r.caseId(),r.labelStatus(),r.expectedDecision(),null,null,r.system(),r.system(),r.input());
        // Unlike assessmentOnlyDiagnostic, reject unexpected auxiliary fields using the actual default contract.
        return UtteranceDirectionTrial.assess(task,response,false,false);
    }
    static Plan prepare(byte[] source) throws Exception {
        check(SOURCE_SHA.equals(ReferenceOnlyTrial.sha(source)),"Frozen source plan hash mismatch");
        return plan(JSON.readValue(source,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(source));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==2,"Frozen source plan and NEW private directory required; prepare only");
        var plan=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[0])));
        Path output=Path.of(args[1]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        byte[] bytes=JSON.writeValueAsBytes(plan);
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        System.out.println("DEFAULT_VERIFICATION_PREPARED calls=56 providerCalls=0 reservation="+plan.reservationEstimatedUsd()
                +" planSha256="+ReferenceOnlyTrial.sha(bytes));
    }
}
