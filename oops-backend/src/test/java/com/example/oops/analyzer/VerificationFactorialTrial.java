package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.VerificationFactorialPlan.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Explicit paid entry point, never called by JUnit, Spring or the prepare-only task. */
public final class VerificationFactorialTrial {
    static final String CONSENT="NEW_USD100_FACTORIAL_VERIFICATION_TEXT_EGRESS_APPROVED";
    static final int MAX_ATTEMPTS=112;
    static final double CAP_USD=1.0;
    record Slot(int repeat,Request request) {}
    static Plan authorize(byte[] plan,String approvedSha,String consent) throws Exception {
        check(ReferenceOnlyTrial.sha(plan).equals(approvedSha) && CONSENT.equals(consent),"Frozen plan and fresh USD1/text approval required");
        return JSON.readValue(plan,Plan.class);
    }
    static void validate(Plan plan,byte[] source,byte[] human,byte[] mappings) throws Exception {
        var regenerated=prepare(source,human,mappings);
        check(JSON.valueToTree(regenerated).equals(JSON.valueToTree(plan)),"Plan differs from frozen source/approval/assembly");
        check(plan.maxRequests()==MAX_ATTEMPTS && plan.repeats()==2 && Double.isFinite(plan.reservationEstimatedUsd())
                && plan.reservationEstimatedUsd()>0 && plan.reservationEstimatedUsd()<=CAP_USD,"Known bounded factorial plan required");
    }
    static List<Slot> schedule(Plan plan) {
        var cases=new LinkedHashSet<String>();plan.requests().forEach(r -> cases.add(r.caseId()));
        var result=new ArrayList<Slot>();
        for(int repeat=1;repeat<=plan.repeats();repeat++) for(String id:cases) for(var arm:order(repeat)) {
            var matches=plan.requests().stream().filter(r -> r.caseId().equals(id) && r.arm()==arm).toList();
            check(matches.size()==1,"Unique request per case and arm required");
            result.add(new Slot(repeat,matches.get(0)));
        }
        check(result.size()==MAX_ATTEMPTS,"112 scheduled calls required");return List.copyOf(result);
    }
    static final class Budget {
        final Path output;int attempts;double reserved;
        Budget(Path output) { this.output=output; }
        void reserve(int bytes) throws Exception {
            check(bytes>=0,"Invalid request length");
            double next=(bytes+1024L)*.10/1_000_000+8000*.50/1_000_000;
            check(attempts<MAX_ATTEMPTS && Double.isFinite(reserved) && reserved+next<=CAP_USD,"Fresh budget or 112 HTTP attempt cap exhausted");
            // Persist before sending: a failed write prevents the provider request.
            Files.writeString(output.resolve("reservation.json"),JSON.writeValueAsString(Map.of("newEstimatedCapUsd",CAP_USD,
                    "httpAttempts",attempts+1,"maxHttpAttempts",MAX_ATTEMPTS,"reservedEstimatedUsd",reserved+next,
                    "inputPer1m",.10,"outputPer1m",.50,"actualBillingConfirmed",false)));
            attempts++;reserved+=next;
        }
    }
    static Map<String,Object> score(Request request,CandidateReviewEngine.VerificationResult response,Map<String,Object> assessment) {
        String decision=null;
        if(response!=null && response.verifications()!=null && response.verifications().size()==1
                && response.verifications().get(0)!=null && response.verifications().get(0).assessment()!=null)
            decision=response.verifications().get(0).assessment().decision();
        boolean labeled=request.expectedDecision()!=null;
        var result=new LinkedHashMap<String,Object>();result.put("returnedDecision",decision);
        result.put("decisionMatchesUserBoundary",labeled?request.expectedDecision().equals(decision):null);
        result.put("acceptedMatchingDecision",labeled?request.expectedDecision().equals(decision) && Boolean.TRUE.equals(assessment.get("accepted")):null);
        result.put("evaluationScope",labeled?"USER_DEVELOPMENT_BOUNDARY_NOT_INDEPENDENT_GOLD":"UNAPPROVED_DRAFT_FORMAT_OBSERVATION_ONLY");
        return result;
    }
    static void save(Path output,String sha,List<Map<String,Object>> rows,Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion","verification-factorial-results-1",
                "state",state,"planSha256",sha,"results",rows,"httpAttempts",budget.attempts,"maxHttpAttempts",MAX_ATTEMPTS,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"newEstimatedCapUsd",CAP_USD,"actualBillingConfirmed",false)));
    }
    static void execute(Plan plan,String sha,MechanismRoutingTrial.Client client,Budget budget,Path output) throws Exception {
        var slots=schedule(plan);var rows=new ArrayList<Map<String,Object>>();double cost=0;
        save(output,sha,rows,budget,cost,"RUNNING");
        try {
            for(var slot:slots) {
                var request=slot.request();client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("factorial-verification");
                try {
                    long start=System.nanoTime();
                    var response=client.completeAsJson(request.system(),request.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                    var usage=client.videoUsage();cost+=usage.costUsd();var assessment=assess(request,response);
                    var row=new LinkedHashMap<String,Object>();row.put("caseId",request.caseId());row.put("arm",request.arm());row.put("repeat",slot.repeat());
                    row.put("labelStatus",request.labelStatus());row.put("expectedDecision",request.expectedDecision());row.put("assessment",assessment);
                    row.put("boundaryEvaluation",score(request,response,assessment));row.put("calls",List.copyOf(client.calls));row.put("usage",usage);
                    row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);save(output,sha,rows,budget,cost,"RUNNING");
                    System.out.println("FACTORIAL_TRIAL "+rows.size()+"/112 "+request.caseId()+" "+request.arm()+" repeat="+slot.repeat());
                    check(client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1,"Provider/usage failure; study stopped without automatic rerun");
                } finally { client.endVideo(); }
            }
            check(rows.size()==MAX_ATTEMPTS && budget.attempts==MAX_ATTEMPTS,"Incomplete HTTP accounting; no completion");
            save(output,sha,rows,budget,cost,"COMPLETE");
        } catch(Exception ex) { save(output,sha,rows,budget,cost,"STOPPED");throw ex; }
    }
    public static void main(String[] args) throws Exception {
        check(args.length==7,"plan approved-sha fresh-consent historical-source human-snapshot mappings NEW-output-dir");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));var plan=authorize(bytes,args[1],args[2]);
        validate(plan,OfflineDecisionContractAudit.bounded(Path.of(args[3])),OfflineDecisionContractAudit.bounded(Path.of(args[4])),
                OfflineDecisionContractAudit.bounded(Path.of(args[5])));
        Path output=Path.of(args[6]).toAbsolutePath().normalize();Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required; no overwrite/resume");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);var budget=new Budget(output);
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { budget.reserve(guarded.length); } catch(java.io.IOException ex) { throw ex; }
                    catch(Exception ex) { throw new IllegalArgumentException("Factorial reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        execute(plan,args[1],new MechanismRoutingTrial.Client(builder.build(),props),budget,output);
    }
}
