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
        claimRun(planFile,output,PLAN_SHA,CONSENT,56);
    }
    static void claimRun(Path planFile,Path output,String sha,String consent,int attempts) throws Exception {
        Files.writeString(planFile.toRealPath().getParent().resolve("execution-claim.json"),
                JSON.writeValueAsString(Map.of("planSha256",sha,"consent",consent,"output",output.toString(),
                        "maxHttpAttempts",attempts,"resumable",false)),StandardOpenOption.CREATE_NEW);
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
        return r.expectedDecision()==null?"DRAFT_UNAPPROVED":r.caseId().startsWith("groq-v4-") || r.caseId().startsWith("R01-")?"ACTUAL_APPROVED":"SYNTHETIC_APPROVED";
    }
    static Map<String,Object> audit(DefaultVerificationPlan.Plan plan,JsonNode saved) throws Exception {
        return auditRequests(plan.requests(),PLAN_SHA,2,saved);
    }
    static Map<String,Object> auditRequests(List<DefaultVerificationPlan.Request> requests,String sha,int repeatsPerCase,JsonNode saved) throws Exception {
        check("COMPLETE".equals(saved.path("state").asText()) && saved.path("httpAttempts").asInt()==requests.size()
                && sha.equals(saved.path("planSha256").asText()) && saved.path("results").size()==requests.size(),"Complete exact-plan results required");
        var totals=new LinkedHashMap<String,Map<String,Integer>>();
        var repeats=new LinkedHashMap<String,List<String>>();
        for(int i=0;i<requests.size();i++) {
            var request=requests.get(i);var row=saved.path("results").get(i);
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
        long disagreement=repeats.values().stream().filter(v -> v.size()!=repeatsPerCase || v.stream().distinct().count()!=1).count();
        return Map.of("providerCalls",0,"historicalContractDrift",0,"totals",totals,
                "decisionOrContractRepeatDisagreements",disagreement,"independentGold",false,
                "evidenceMeaningAutomaticallyVerified",false,"runtimePromotion",false);
    }
    record FrozenRun(String sha,String consent,String model,int maxAttempts,int repeats,String resultSchema,
                     String approvalSource,List<DefaultVerificationPlan.Request> requests) {}
    static void save(Path output,FrozenRun run,List<Map<String,Object>> rows,StructuredSearchBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion",run.resultSchema(),
                "planSha256",run.sha(),"state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,"maxHttpAttempts",run.maxAttempts())));
    }
    static void reserveBounded(StructuredSearchBoundaryTrial.Budget budget,int bytes,int maxAttempts) throws Exception {
        check(maxAttempts==48 || maxAttempts==56,"Known fixed request count required");
        if(budget.attempts>=maxAttempts) { budget.exhausted=true;throw new IllegalArgumentException("HTTP attempt cap reached"); }
        budget.reserve(bytes);
        Files.writeString(budget.output.resolve("reservation.json"),JSON.writeValueAsString(Map.of("newEstimatedCapUsd",budget.cap,
                "httpAttempts",budget.attempts,"maxHttpAttempts",maxAttempts,"reservedEstimatedUsd",budget.reserved,
                "inputPer1m",.10,"outputPer1m",.50,"actualBillingConfirmed",false)));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==5,"Frozen plan, source plan, approved sha, fresh consent and NEW private output required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var plan=authorized(bytes,OfflineDecisionContractAudit.bounded(Path.of(args[1])),args[2],args[3]);
        runFrozen(new FrozenRun(PLAN_SHA,CONSENT,plan.model(),56,2,"default-verification-results-1",
                "USER_APPROVED_FROZEN_DEFAULT_COMPARISON_20261017",plan.requests()),bytes,Path.of(args[0]),Path.of(args[4]));
    }
    static void runFrozen(FrozenRun run,byte[] bytes,Path planFile,Path destination) throws Exception {
        check(ReferenceOnlyTrial.sha(bytes).equals(run.sha()) && run.requests().size()==run.maxAttempts()
                && (run.maxAttempts()==48 || run.maxAttempts()==56)
                && DefaultVerificationPlan.reservation(run.requests(),run.model())<=.50,"Frozen run accounting mismatch");
        Path output=destination.toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required; no resume");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        claimRun(planFile,output,run.sha(),run.consent(),run.maxAttempts());
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"),JSON.writeValueAsString(Map.of("newEstimatedCapUsd",.50,
                "consent",run.consent(),"planSha256",run.sha(),"textEgressApproved",true,"audioVideoEgressApproved",false,
                "previousBudgetReused",false,"approvalSource",run.approvalSource())));
        var budget=new StructuredSearchBoundaryTrial.Budget(output,.50);var guard=new RequestGuard();
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",run.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=guard.accept(body,request.getURI().toString());
                    try { reserveBounded(budget,guarded.length,run.maxAttempts()); }
                    catch(Exception ex) { throw new IllegalArgumentException("Reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(builder.build(),props);
        var rows=new ArrayList<Map<String,Object>>();double cost=0;save(output,run,rows,budget,cost,"RUNNING");
        try {
            for(var request:run.requests()) {
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("default-verification-comparison");
                guard.begin(JSON.writeValueAsBytes(client.replayRequestBody(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class)));
                long start=System.nanoTime();int before=budget.attempts;
                var response=client.completeAsJson(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage=client.videoUsage();cost+=usage.costUsd();
                var row=new LinkedHashMap<String,Object>();row.put("caseId",request.caseId());row.put("arm",request.arm());
                row.put("repeat",request.repeat());row.put("labelStatus",request.labelStatus());row.put("expectedDecision",request.expectedDecision());
                row.put("assessment",DefaultVerificationPlan.assess(request,response));row.put("calls",List.copyOf(client.calls));
                row.put("usage",usage);row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);
                save(output,run,rows,budget,cost,"RUNNING");
                boolean ok=!budget.exhausted && budget.attempts-before==1 && client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;
                client.endVideo();System.out.println("FROZEN_VERIFICATION_TRIAL "+rows.size()+"/"+run.maxAttempts()+" "+request.caseId()+" "+request.arm()+" repeat="+request.repeat());
                check(ok,"Provider/budget/usage failure; stop without replay or extra calls");
            }
            check(budget.attempts==run.maxAttempts(),"HTTP accounting mismatch");save(output,run,rows,budget,cost,"COMPLETE");
            var report=auditRequests(run.requests(),run.sha(),run.repeats(),JSON.readTree(Files.readAllBytes(output.resolve("results.json"))));
            Files.writeString(output.resolve("offline-audit.json"),JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
            System.out.println("FROZEN_VERIFICATION_COMPLETE usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
        } catch(Exception ex) { save(output,run,rows,budget,cost,"STOPPED");throw ex; }
    }
}
