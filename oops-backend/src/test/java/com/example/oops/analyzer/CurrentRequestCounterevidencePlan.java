package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Test-only prompt comparison preparation. No production wiring, keys, calls or relaxed validation. */
public final class CurrentRequestCounterevidencePlan {
    static final String VERSION="current-request-counterevidence-plan-20261017-1";
    static final String INPUT_SHA="b463b3658b44f9fdceab65f3b13bbfc8054991a1e4710bfc2ddf3fb1f13f5afa";
    static final String LABEL_SHA="8ca23b42f717e27c2b524912f09f87a734e0cd4d1d261aab262a743e9c02d8e9";
    static final String OLD="""
            먼저 proposedEvidence의 평가·대상·앞뒤 연결을 실제 raw에서 확인한 뒤 허용 문맥을 대조한다.
              anchor가 질문·대명사라는 이유만으로 그 문장만 떼어 PASS 처리하지 않는다.
              전체 연결에도 구체적인 낮춤이 없으면 PASS다. 시간 인접·부정 표현 누적만으로 문제를 만들지 않는다.
            """;
    static final String NEW="""
            먼저 후보별 허용 raw 전체에서 anchor가 이어받는 질문·설명·인용을 확인한 뒤 proposedEvidence를 대조한다.
              뜻을 묻는 질문 뒤의 질문문은 설명된 문장 내용과 현재 상대에게 하는 요구를 구별한다.
              이를 현재 상대에 대한 실제 요구로 판단하려면 뜻풀이 해석과 구별되는 답변·행동 요구의 원문 근거가 필요하다.
              뒤의 다른 행위만으로 앞 발언의 행위·대상을 바꾸지 않는다. 별도 모욕·위협·대상화 근거는 독립적으로 검토한다.
              뜻풀이·인용·자기 제안이라는 이유로 자동 PASS 처리하지 않으며, 성적 소재라는 이유로 실제 상대 요구를 만들지 않는다.
              전체 연결에 구체적인 검토 이유가 없으면 PASS다. 시간 인접·부정 표현 누적만으로 문제를 만들지 않는다.
            """;
    static final List<String> IDS=List.of("R03","R04","R05","R06","R07","R08","R01-166","R01-178");
    static final Map<String,String> SOURCES=Map.of("R03","relation-self-offer","R04","relation-other-request",
            "R05","relation-term-definition","R06","relation-critical-quotation","R07","relation-ambiguous-actor",
            "R08","relation-refusal-not-acceptance","R01-166","groq-v4-166","R01-178","groq-v4-178");
    record Plan(String schemaVersion,String model,String engineRevision,String baselinePlanSha256,String inputsSha256,
                String labelsSha256,int repeats,int maxHttpAttempts,double estimatedCapUsd,double reservationEstimatedUsd,
                boolean independentGold,boolean freshCostAndTextApprovalRequired,String scope,List<String> constructionNotes,
                List<DefaultVerificationPlan.Request> requests) {}
    private CurrentRequestCounterevidencePlan() {}

    static String comparison(String baseline) {
        int boundary=baseline.indexOf("# 대상 필드 형식 예시");
        check(baseline.startsWith(CandidateReviewPrompts.POLICY) && boundary>=0,"Known default verification prompt required");
        String body=baseline.substring(0,boundary);
        check(body.indexOf(OLD)>=0 && body.indexOf(OLD)==body.lastIndexOf(OLD),"Single original interpretation paragraph required");
        return body.replace(OLD,NEW)+baseline.substring(boundary);
    }
    static JsonNode projected(JsonNode raw) {
        var result=JSON.createArrayNode();
        for(var r:raw) {
            var n=result.addObject();
            for(String key:List.of("id","text","startMs","endMs")) { check(r.has(key),"Complete source rows required");n.set(key,r.get(key)); }
        }
        return result;
    }
    static String fixedInput(DefaultVerificationPlan.Request source,JsonNode approvedRaw,JsonNode fullR01Raw,boolean expanded) {
        var input=(ObjectNode)JSON.readTree(source.input());
        if(expanded) {
            input.set("raw",fullR01Raw.deepCopy());
            var candidate=(ObjectNode)input.path("candidates").get(0);
            var ids=candidate.putArray("segmentIds");approvedRaw.forEach(r -> ids.add(r.path("id").asText()));
        }
        check(projected(input.path("raw")).equals(projected(approvedRaw)),"Frozen input differs from approved full window");
        check(input.path("candidates").size()==1 && !input.has("expectedDecision") && !input.has("userAnswer"),"Unlabeled single-candidate input required");
        return expanded?input.toString():source.input();
    }
    static Plan build(DefaultVerificationPlan.Plan source,JsonNode inputs,JsonNode labels,String sourceSha,String inputSha,String labelSha) {
        check(inputs.size()==8 && labels.size()==8 && source.model().equals("gpt-6-luna"),"Eight approved conditions required");
        var baseline=new LinkedHashMap<String,DefaultVerificationPlan.Request>();
        for(var r:source.requests()) if(r.arm().equals("KEPT") && r.repeat()==1) check(baseline.put(r.caseId(),r)==null,"Duplicate frozen source");
        var raw166=JSON.readTree(Objects.requireNonNull(baseline.get("groq-v4-166")).input()).path("raw");
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        for(int repeat=1;repeat<=3;repeat++) for(int i=0;i<8;i++) {
            String id=IDS.get(i);var data=inputs.get(i);var label=labels.get(i);
            check(id.equals(data.path("caseId").asText()) && id.equals(label.path("caseId").asText()),"Approved case order mismatch");
            String expected=Set.of("R04","R08").contains(id)?"REVIEW_REQUIRED":"PASS";
            check(expected.equals(label.path("expectedDecision").asText()),"Approved decision mismatch");
            var old=Objects.requireNonNull(baseline.get(SOURCES.get(id)),"Frozen candidate missing");
            String system=CandidateReviewEngine.verificationPrompt(0)+"\n"+DefaultVerificationPlan.reference(old.system());
            check(system.equals(old.system()),"Current default prompt changed; new baseline required");
            String input=fixedInput(old,data.path("raw"),raw166,id.equals("R01-178"));
            check(JSON.readTree(input).at("/candidates/0/anchorId").asText().equals(data.path("anchorId").asText()),"Approved anchor mismatch");
            for(String arm:repeat%2==1?List.of("BASELINE","COUNTEREVIDENCE"):List.of("COUNTEREVIDENCE","BASELINE"))
                requests.add(new DefaultVerificationPlan.Request(id,"USER_APPROVED_DEVELOPMENT_NOT_INDEPENDENT_GOLD",expected,
                        arm,repeat,arm.equals("BASELINE")?system:comparison(system),input));
        }
        double estimate=DefaultVerificationPlan.reservation(requests,source.model());
        check(requests.size()==48 && estimate<=.50,"Fixed comparison exceeds proposal cap");
        return new Plan(VERSION,source.model(),CandidateReviewEngine.REVISION,sourceSha,inputSha,labelSha,3,48,.50,estimate,
                false,true,"FIXED_CANDIDATE_VERIFICATION_ONLY_NOT_DISCOVERY_OR_FULL_PIPELINE",
                List.of("Both arms keep hypothesis, proposed evidence, references, JSON contract and validator.",
                        "R01-178 is a NEW approved 32-row input: raw/segmentIds expanded from historical 27-row request before both arms.",
                        "Original candidate anchor, proposed evidence and frozen references are retained; no new retrieval.",
                        "One real dialogue with two anchor issues is not two independent videos."),List.copyOf(requests));
    }
    static Plan prepare(byte[] source,byte[] inputs,byte[] labels) throws Exception {
        check(ReferenceOnlyTrial.sha(source).equals(DefaultVerificationTrial.PLAN_SHA)
                && ReferenceOnlyTrial.sha(inputs).equals(INPUT_SHA) && ReferenceOnlyTrial.sha(labels).equals(LABEL_SHA),"Frozen approved sources required");
        return build(JSON.readValue(source,DefaultVerificationPlan.Plan.class),JSON.readTree(inputs),JSON.readTree(labels),
                ReferenceOnlyTrial.sha(source),INPUT_SHA,LABEL_SHA);
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Frozen baseline plan, approved bundle directory, NEW private output; prepare only");
        Path bundle=Path.of(args[1]);
        var plan=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[0])),
                OfflineDecisionContractAudit.bounded(bundle.resolve("inputs.json")),OfflineDecisionContractAudit.bounded(bundle.resolve("labels.json")));
        Path output=Path.of(args[2]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required");
        byte[] bytes=JSON.writeValueAsBytes(plan);Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        System.out.println("COUNTEREVIDENCE_PREPARED calls=48 providerCalls=0 reservation="+plan.reservationEstimatedUsd()+" planSha256="+ReferenceOnlyTrial.sha(bytes));
    }
}
