package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One explicitly requested rerun; original frozen implementation and failed result stay unchanged. */
public final class LongVideoRoutingRetry {
    static final String CONSENT="RERUN_V4_V6_WITHIN_ORIGINAL_USD3_APPROVED_20261016";
    static final String PLAN_SHA="51c5d6e0cbd3fb0142d5b2c669f98a9f895b04a3abdfc0d7a7f4dfd49e19da30";
    static final String PRIOR_SHA="7343d614f7d7ae26017e299098ab31716bcc0e3fad98c6079e6bf2e4a03d902d";

    static LongVideoRoutingTrial.Budget carryBudget(Path output,byte[] previous) throws Exception {
        check(ReferenceOnlyTrial.sha(previous).equals(PRIOR_SHA),"Exact preserved prior result required");
        var prior=JSON.readTree(previous);
        check(prior.path("planSha256").asText().equals(PLAN_SHA)
                && prior.path("state").asText().equals("STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE")
                && prior.path("authorization").asText().equals(LongVideoRoutingTrial.CONSENT)
                && prior.path("approvedEstimatedUsd").asDouble()==3,"Original approved scope required");
        var budget=new LongVideoRoutingTrial.Budget(output);
        budget.attempts=prior.path("httpAttempts").asInt();
        budget.reserved=prior.path("reservedEstimatedUsd").asDouble();
        check(budget.attempts==97 && budget.reserved>0 && budget.reserved<3,"Prior reservations must carry forward");
        return budget;
    }
    static void save(Path output,LongVideoRoutingTrial.Plan plan,List<Map<String,Object>> rows,
                     LongVideoRoutingTrial.Budget budget,double cost,String state,byte[] prior) throws Exception {
        LongVideoRoutingTrial.save(output,plan,PLAN_SHA,rows,budget,cost,state);
        var report=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Files.readAllBytes(output.resolve("results.json")));
        report.put("authorization",CONSENT);
        report.put("previousResultsSha256",PRIOR_SHA);
        report.put("previousUsageEstimatedUsd",JSON.readTree(prior).path("usageEstimatedUsd").asDouble());
        report.put("retryUsageEstimatedUsd",cost-JSON.readTree(prior).path("usageEstimatedUsd").asDouble());
        report.put("retryHttpAttempts",budget.attempts-97);
        report.put("budgetScope","CUMULATIVE_ORIGINAL_ATTEMPT_PLUS_ONE_USER_REQUESTED_RERUN");
        report.put("priorFailedRequestUsageUnknown",true);
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(report));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4 && CONSENT.equals(args[2]),"Exact plan, preserved prior result, rerun consent, NEW private dir required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        var p=LongVideoRoutingTrial.authorized(bytes,PLAN_SHA,LongVideoRoutingTrial.CONSENT);
        byte[] prior=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().equals(Path.of("../datasets/controversy/experiments").toRealPath()),
                "New direct private experiment directory required; no overwrite");
        var budget=carryBudget(output,prior);
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output);
        Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("previous-results.json"),prior,StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("retry-manifest.json"),JSON.writeValueAsString(Map.of(
                "authorization",CONSENT,"planSha256",PLAN_SHA,"previousResultsSha256",PRIOR_SHA,
                "retryImplementationSha256",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/LongVideoRoutingRetry.java"))),
                "analyses",8,"carryForwardHttpAttempts",budget.attempts,"carryForwardReservation",budget.reserved,
                "automaticRerun",false,"actualBillingConfirmed",false)));
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),"https://api.openai.com/v1",p.model(),
                "none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] bounded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { budget.reserve(bounded.length); } catch(java.io.IOException|RuntimeException e) { throw e; }
                    catch(Exception e) { throw new java.io.IOException("Cumulative reservation persistence failed",e); }
                    request.getHeaders().setContentLength(bounded.length); return execution.execute(request,bounded);
                });
        if(props.hasOrganization()) rest.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) rest.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(rest.build(),props);
        var library=ContextRankReadinessAudit.library(p.runtimeText());
        var router=new ContextMechanismRouter(client,MechanismRoutingTrial.resources(p.registryText()),true,"classpath:frozen.json",p.maxRouterCalls());
        router.load();
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=JSON.readTree(prior).path("usageEstimatedUsd").asDouble();
        save(output,p,rows,budget,cost,"RUNNING",prior);
        for(int repeat=1;repeat<=2;repeat++) for(var s:p.sources()) for(String arm:repeat==1?List.of("OFF","ON"):List.of("ON","OFF")) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("v7-v4-v6-routing-trial"); long start=System.nanoTime();
            var condition=new MechanismRoutingTrial.Condition(s.caseId(),s.labelStatus(),s.raw());
            var result=CandidateReviewEngine.run(client,MechanismRoutingTrial.context(condition),24,null,null,library,null,0,false,arm.equals("ON")?router:null);
            var usage=client.videoUsage(); cost+=usage.costUsd();
            var row=new LinkedHashMap<String,Object>(); row.put("caseId",s.caseId()); row.put("sourceVideoId",s.videoId()); row.put("rawSha256",s.rawSha256());
            row.put("labelStatus",s.labelStatus()); row.put("arm",arm); row.put("repeat",repeat); row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);
            row.put("status",result.status()); row.put("diagnostics",result.diagnostics()); row.put("evaluations",result.evaluations());
            row.put("unassessedSegmentIds",result.unassessedSegmentIds()); row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
            row.put("findings",result.findings().stream().map(f -> Map.of("category",f.getCategory(),"text",f.primaryText(),"reason",f.getReason(),
                    "target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList()); rows.add(row);
            boolean ok=!budget.exhausted && cost<=3 && client.failureCount()==0 && usage.calls()==client.calls.size();
            save(output,p,rows,budget,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE",prior);
            System.out.println("LONG_ROUTING_RETRY "+rows.size()+"/8 "+s.caseId()+" "+arm+" repeat="+repeat+" findings="+result.findings().size()+" status="+result.status()+" calls="+usage.calls());
            client.endVideo(); check(ok,"Rerun stopped; preserve results, do not automatically repeat");
        }
        save(output,p,rows,budget,cost,"COMPLETE",prior);
        System.out.println("LONG_ROUTING_RETRY_COMPLETE analyses=8 cumulativeHttp="+budget.attempts+" cumulativeUsageEstimatedUsd="+cost+" cumulativeReserved="+budget.reserved);
    }
}
