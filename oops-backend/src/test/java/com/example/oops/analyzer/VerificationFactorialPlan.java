package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Prepare-only 2x2 ablation. No provider execution, consent reuse or production wiring. */
public final class VerificationFactorialPlan {
    static final String VERSION="verification-contract-guard-factorial-1";
    static final String MARKER="# 대상 필드 형식 예시";
    // Exact contiguous instruction block from the historical mixed contract, not new judgment wording.
    static final String GUARD="""
              성적 단어·사생활 질문·자발적 제안 자체만으로 경고하지 않는다. 특정인을 성적으로 대상화한다는
              판단을 대상 근거 없이 통과시키려고 EXPRESSION_CONTENT로 바꾸지 않는다. 별도의 표현 자체 근거가 필요하다.
              비하·조롱·집단 일반화·차별·혐오 등 대상 평가를 표현 자체로 바꿔 대상 검사를 피하지 않는다.
              현재 raw와 segmentIds 밖의 문맥을 추가하거나 새 화자·동의·실제 실행을 추정하지 않는다.
            """;
    enum Arm {
        BASELINE(false,false), GUARD_ONLY(false,true), CONTRACT_ONLY(true,false), BOTH(true,true);
        final boolean basis,guard;
        Arm(boolean basis,boolean guard) { this.basis=basis;this.guard=guard; }
    }
    record Request(String caseId,Arm arm,String labelStatus,String expectedDecision,String system,String input) {}
    record Plan(String schemaVersion,String model,int repeats,int maxRequests,String sourcePlanSha256,
                String humanReviewSha256,String mappingsSha256,List<Request> requests,double reservationEstimatedUsd) {}
    static int marker(String prompt) {
        int i=prompt.indexOf(MARKER);
        check(i>=0 && prompt.startsWith(CandidateReviewPrompts.POLICY),"Known verification instructions required");
        return i;
    }
    static String system(String baseline,Arm arm) {
        marker(baseline);
        String mixed=FinalReviewBasisContract.prompt(baseline);
        int offset=mixed.indexOf(GUARD);
        check(offset>=0 && offset<marker(mixed),"Historical guard block changed");
        String contract=mixed.substring(0,offset)+mixed.substring(offset+GUARD.length());
        String selected=arm.basis?contract:baseline;
        if(!arm.guard) return selected;
        int insertion=marker(selected);
        return selected.substring(0,insertion)+GUARD+selected.substring(insertion);
    }
    static String input(String baseline,Arm arm) {
        var node=(tools.jackson.databind.node.ObjectNode)JSON.readTree(baseline);
        check(!node.has("verificationContract") && !node.has("expectedDecision") && !node.has("labelStatus"),"Unlabeled baseline input required");
        if(!arm.basis) return baseline;
        node.put("verificationContract",FinalReviewBasisContract.VERSION);
        return node.toString();
    }
    static List<Arm> order(int repeat) {
        check(repeat==1 || repeat==2,"Two repeats only");
        return repeat==1?List.of(Arm.values()):List.of(Arm.BOTH,Arm.CONTRACT_ONLY,Arm.GUARD_ONLY,Arm.BASELINE);
    }
    static Map<String,Object> assess(Request request,CandidateReviewEngine.VerificationResult response) {
        var task=new UtteranceDirectionTrial.Task(request.caseId(),request.labelStatus(),request.expectedDecision(),
                null,null,request.system(),request.system(),request.input());
        return UtteranceDirectionTrial.assess(task,response,request.arm().basis);
    }
    static Plan prepare(byte[] source,byte[] human,byte[] mapping) throws Exception {
        var original=JSON.readValue(source,UtteranceDirectionTrial.Plan.class);
        UtteranceDirectionTrial.validate(original);
        check(UtteranceDirectionTrial.basis(original),"Frozen historical basis plan required");
        var form=JSON.readTree(human);var mappings=JSON.readTree(mapping);
        check("utterance-human-review-form-1".equals(form.path("schemaVersion").asText())
                && "USER_APPROVED_DEVELOPMENT_LABELS".equals(form.path("reviewStatus").asText())
                && form.path("cases").size()==8 && mappings.path("cases").size()==8
                && form.path("blindPacketCanonicalSha256").asText().matches("[a-f0-9]{64}")
                && form.path("blindPacketCanonicalSha256").equals(mappings.path("blindPacketCanonicalSha256")),"Eight approved scoped labels required");
        var labels=new HashMap<String,String>();var ids=new HashSet<String>();
        for(var c:form.path("cases")) {
            String review=c.path("reviewId").asText(),decision=c.path("expectedDecision").asText();
            check(ids.add(review) && "USER_APPROVED_DEVELOPMENT_LABEL".equals(c.path("reviewStatus").asText())
                    && Set.of("PASS","REVIEW_REQUIRED").contains(decision),"No pending or duplicate approvals");
            var matches=new ArrayList<tools.jackson.databind.JsonNode>();
            mappings.path("cases").forEach(m -> { if(review.equals(m.path("reviewId").asText())) matches.add(m); });
            check(matches.size()==1,"Unique source mapping required");
            var m=matches.get(0);String caseId=m.path("sourceCaseId").asText();
            var t=original.tasks().stream().filter(x -> x.id().equals(caseId)).findFirst().orElseThrow();
            check(ReferenceOnlyTrial.sha(t.input().getBytes(StandardCharsets.UTF_8)).equals(m.path("inputSha256").asText())
                    && labels.put(caseId,decision)==null,"Approval must bind exact source input");
        }
        check(Collections.frequency(new ArrayList<>(labels.values()),"PASS")==6 && labels.size()==8,"Known six PASS/two review development set required");
        var requests=new ArrayList<Request>();
        for(var t:original.tasks()) for(var arm:Arm.values()) requests.add(new Request(t.id(),arm,
                labels.containsKey(t.id())?"USER_APPROVED_DEVELOPMENT_LABEL_NOT_INDEPENDENT_GOLD":t.labelStatus(),
                labels.get(t.id()),system(t.baselineSystem(),arm),input(t.input(),arm)));
        // Unapproved old normal controls retain null expectedDecision, not their historical draft labels.
        check(requests.size()==56,"Four arms for fourteen frozen conditions required");
        double reservation=reservation(requests,2,original.model());
        return new Plan(VERSION,original.model(),2,112,ReferenceOnlyTrial.sha(source),ReferenceOnlyTrial.sha(human),
                ReferenceOnlyTrial.sha(mapping),List.copyOf(requests),reservation);
    }
    static double reservation(List<Request> requests,int repeats,String model) {
        var properties=new OpenAiProperties("offline-test-key",null,null,"https://api.openai.com/v1",model,"none",Duration.ofSeconds(2),null);
        var client=new OpenAiClient(RestClient.create(),properties);double estimate=0;
        for(var r:requests) {
            var bytes=JSON.writeValueAsBytes(client.replayRequestBody(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class));
            var guarded=V7RoutingVideoTrial.guardBody(bytes,"https://api.openai.com/v1/chat/completions");
            estimate+=repeats*((guarded.length+1024)*.10/1_000_000+8000*.50/1_000_000);
        }
        return estimate;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"prepare-only: historical-plan approved-human-snapshot mappings NEW-output-dir");
        var plan=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[0])),OfflineDecisionContractAudit.bounded(Path.of(args[1])),
                OfflineDecisionContractAudit.bounded(Path.of(args[2])));
        var output=Path.of(args[3]).toAbsolutePath().normalize();var root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        Files.createDirectory(output);var bytes=JSON.writeValueAsBytes(plan);
        Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        System.out.println("FACTORIAL_PREPARED requests=112 providerCalls=0 reservation="+plan.reservationEstimatedUsd()+" planSha256="+ReferenceOnlyTrial.sha(bytes));
    }
}
