package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Freshly approved fixed-request execution only; not wired into the application. */
public final class DefaultVerificationTrial {
    static final String PLAN_SHA="075f003b1803828b4ae851148b707ef67b1fa23b6fb85716d8984b2c48594b28";
    static final String CONSENT="NEW_USD050_DEFAULT_VERIFICATION_TEXT_EGRESS_APPROVED";
    static final String ENDPOINT="https://api.openai.com/v1/chat/completions";
    private DefaultVerificationTrial() {}
    static void claim(Path planFile,Path output) throws Exception {
        Files.writeString(planFile.toRealPath().getParent().resolve("execution-claim.json"),
                JSON.writeValueAsString(Map.of("planSha256",PLAN_SHA,"consent",CONSENT,"output",output.toString(),
                        "maxHttpAttempts",56,"resumable",false)),StandardOpenOption.CREATE_NEW);
    }
    static DefaultVerificationPlan.Plan authorized(byte[] bytes,byte[] source,String sha,String consent) throws Exception {
        check(PLAN_SHA.equals(sha) && sha.equals(ReferenceOnlyTrial.sha(bytes)) && CONSENT.equals(consent),
                "Fresh exact-plan USD050/text consent required");
        var plan=DefaultVerificationPlan.prepare(source);
        check(JSON.readTree(bytes).equals(JSON.valueToTree(plan)),"Current engine or frozen requests changed");
        return plan;
    }
    static final class RequestGuard {
        private byte[] expected;
        private boolean sent;
        void begin(byte[] body) { expected=V7RoutingVideoTrial.guardBody(body,ENDPOINT);sent=false; }
        byte[] accept(byte[] body,String uri) {
            check(expected!=null && !sent,"No extra HTTP attempts or automatic retries allowed");
            byte[] guarded=V7RoutingVideoTrial.guardBody(body,uri);
            check(JSON.readTree(expected).equals(JSON.readTree(guarded)),"Only the frozen system/input/body may leave");
            sent=true;return guarded;
        }
    }
    static String group(DefaultVerificationPlan.Request r) {
        return r.expectedDecision()==null?"DRAFT_UNAPPROVED":r.caseId().startsWith("groq-v4-")?"ACTUAL_APPROVED":"SYNTHETIC_APPROVED";
    }
    static Map<String,Object> audit(DefaultVerificationPlan.Plan plan,JsonNode saved) throws Exception {
        check("COMPLETE".equals(saved.path("state").asText()) && saved.path("httpAttempts").asInt()==56
                && PLAN_SHA.equals(saved.path("planSha256").asText()) && saved.path("results").size()==56,"Complete exact-plan results required");
        var totals=new LinkedHashMap<String,Map<String,Integer>>();
        var repeats=new LinkedHashMap<String,List<String>>();
        for(int i=0;i<56;i++) {
            var request=plan.requests().get(i);var row=saved.path("results").get(i);
            var calls=row.path("calls");
            check(request.caseId().equals(row.path("caseId").asText()) && request.arm().equals(row.path("arm").asText())
                    && request.repeat()==row.path("repeat").asInt() && request.labelStatus().equals(row.path("labelStatus").asText())
                    && JSON.valueToTree(request.expectedDecision()).equals(row.path("expectedDecision"))
                    && calls.size()==1 && "VerificationResult".equals(calls.get(0).path("kind").asText())
                    && calls.get(0).path("failureCode").isNull() && request.system().equals(calls.get(0).path("system").asText())
                    && JSON.readTree(request.input()).equals(calls.get(0).path("input")),"Saved request/label/order mismatch");
            var response=JSON.treeToValue(calls.get(0).path("output"),CandidateReviewEngine.VerificationResult.class);
            var assessment=DefaultVerificationPlan.assess(request,response);
            check(JSON.valueToTree(assessment).equals(row.path("assessment")),"Default validation replay drift");
            boolean accepted=Boolean.TRUE.equals(assessment.get("accepted"));
            String decision=calls.get(0).at("/output/verifications/0/assessment/decision").asText("MISSING");
            for(String scope:List.of("ALL",group(request))) {
                var counts=totals.computeIfAbsent(request.arm()+":"+scope,k -> new LinkedHashMap<>());
                counts.merge("responses",1,Integer::sum);
                if(accepted) counts.merge("contractAccepted",1,Integer::sum);
                counts.merge("decision:"+decision,1,Integer::sum);
                if(request.expectedDecision()!=null && request.expectedDecision().equals(decision)) {
                    counts.merge("approvedDecisionMatch",1,Integer::sum);
                    if(accepted) counts.merge("approvedMatchAndAccepted",1,Integer::sum);
                }
                if(!accepted) counts.merge("failure:"+assessment.get("failureCode"),1,Integer::sum);
            }
            repeats.computeIfAbsent(request.arm()+":"+request.caseId(),k -> new ArrayList<>()).add(decision+":"+accepted);
        }
        long disagreement=repeats.values().stream().filter(v -> v.size()!=2 || !v.get(0).equals(v.get(1))).count();
        return Map.of("providerCalls",0,"historicalContractDrift",0,"totals",totals,
                "decisionOrContractRepeatDisagreements",disagreement,"independentGold",false,
                "evidenceMeaningAutomaticallyVerified",false,"runtimePromotion",false);
    }
    static void save(Path output,List<Map<String,Object>> rows,StructuredSearchBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion","default-verification-results-1",
                "planSha256",PLAN_SHA,"state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,"maxHttpAttempts",56)));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==5,"Frozen plan, source plan, approved sha, fresh consent and NEW private output required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var plan=authorized(bytes,OfflineDecisionContractAudit.bounded(Path.of(args[1])),args[2],args[3]);
        Path output=Path.of(args[4]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required; no resume");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        claim(Path.of(args[0]),output);
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"),JSON.writeValueAsString(Map.of("newEstimatedCapUsd",.50,
                "consent",CONSENT,"planSha256",PLAN_SHA,"textEgressApproved",true,"audioVideoEgressApproved",false,
                "previousBudgetReused",false,"approvalSource","USER_APPROVED_FROZEN_DEFAULT_COMPARISON_20261017")));
        var budget=new StructuredSearchBoundaryTrial.Budget(output,.50);var guard=new RequestGuard();
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=guard.accept(body,request.getURI().toString());
                    try { UtteranceDirectionTrial.reserve(budget,guarded.length,56); }
                    catch(Exception ex) { throw new IllegalArgumentException("Reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(builder.build(),props);
        var rows=new ArrayList<Map<String,Object>>();double cost=0;save(output,rows,budget,cost,"RUNNING");
        try {
            for(var request:plan.requests()) {
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("default-verification-comparison");
                guard.begin(JSON.writeValueAsBytes(client.replayRequestBody(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class)));
                long start=System.nanoTime();int before=budget.attempts;
                var response=client.completeAsJson(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage=client.videoUsage();cost+=usage.costUsd();
                var row=new LinkedHashMap<String,Object>();row.put("caseId",request.caseId());row.put("arm",request.arm());
                row.put("repeat",request.repeat());row.put("labelStatus",request.labelStatus());row.put("expectedDecision",request.expectedDecision());
                row.put("assessment",DefaultVerificationPlan.assess(request,response));row.put("calls",List.copyOf(client.calls));
                row.put("usage",usage);row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);
                save(output,rows,budget,cost,"RUNNING");
                boolean ok=!budget.exhausted && budget.attempts-before==1 && client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;
                client.endVideo();System.out.println("DEFAULT_TRIAL "+rows.size()+"/56 "+request.caseId()+" "+request.arm()+" repeat="+request.repeat());
                check(ok,"Provider/budget/usage failure; stop without replay or extra calls");
            }
            check(budget.attempts==56,"HTTP accounting mismatch");save(output,rows,budget,cost,"COMPLETE");
            var report=audit(plan,JSON.readTree(Files.readAllBytes(output.resolve("results.json"))));
            Files.writeString(output.resolve("offline-audit.json"),JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
            System.out.println("DEFAULT_COMPLETE usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
        } catch(Exception ex) { save(output,rows,budget,cost,"STOPPED");throw ex; }
    }
}
