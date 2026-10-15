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
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Exact-plan, single-attempt comparative trial. Not wired into production retrieval. */
public final class ContextSemanticRankExecution {
    static final String CONSENT="NEW_USD_1_PROFILE_AND_SYNTHETIC_TEXT_EGRESS_APPROVED_20261016";
    static ContextSemanticRankPreparation.Plan authorized(byte[] bytes,String sha,String consent) throws Exception {
        check(CONSENT.equals(consent) && ReferenceOnlyTrial.sha(bytes).equals(sha),"Exact-plan fresh consent required");
        var p=JSON.readValue(bytes,ContextSemanticRankPreparation.Plan.class);
        check("context-semantic-rank-plan-1".equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model())
                && p.tasks().size()==24 && p.promptSha256().equals(ReviewGuidelineLibrary.retrievalHash(ContextSemanticRankPreparation.PROMPT)),"Frozen plan required");
        Set<String> ids=new HashSet<>();
        for(var t:p.tasks()) {
            check(ids.add(t.id()) && t.system().equals(ContextSemanticRankPreparation.PROMPT)
                    && t.inputSha256().equals(ReviewGuidelineLibrary.retrievalHash(t.input())),"Invalid task hash/prompt/id");
            var input=JSON.readTree(t.input());
            check(input.size()==2 && input.has("raw") && input.has("profiles") && t.input().codePointCount(0,t.input().length())<=8000,"Invalid input boundary");
            Set<String> aliases=new HashSet<>(); input.path("profiles").forEach(v -> aliases.add(v.path("id").asText()));
            check(aliases.equals(t.localIdMap().keySet()) && aliases.size()>=2 && aliases.size()<=3,"Invalid profile pool");
        }
        return p;
    }
    static Map<String,Object> metrics(List<Map<String,Object>> rows) {
        var source=rows.stream().filter(r -> r.get("queryKind").equals("SOURCE")).toList();
        return Map.of("completed",rows.size(),"sourceRequests",source.size(),
                "sourceStrictFirst",source.stream().filter(r -> Boolean.TRUE.equals(r.get("strictFirst"))).count(),
                "sourceTopGroupIncludesTarget",source.stream().filter(r -> Boolean.TRUE.equals(r.get("topIncludesTarget"))).count(),
                "formatFailures",rows.stream().filter(r -> r.get("failure")!=null).count());
    }
    static void save(Path output,String sha,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion","context-semantic-rank-results-1"); report.put("planSha256",sha); report.put("state",state);
        report.put("authorization",CONSENT); report.put("approvedEstimatedUsd",1); report.put("httpAttempts",budget.attempts);
        report.put("reservedEstimatedUsd",budget.reserved); report.put("usageEstimatedUsd",cost); report.put("actualBillingConfirmed",false);
        report.put("pricingBasis","CONFIGURED_ESTIMATE_INPUT_0.10_CACHED_0.01_OUTPUT_0.50_PER_1M_NOT_BILLING_CONFIRMATION");
        report.put("verdictAccuracyMeasured",false); report.put("labelStatus","SYNTHETIC_AUTHOR_PROPOSALS_NOT_APPROVED_GOLD");
        report.put("summary",metrics(rows)); report.put("results",rows);
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(report));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Plan, exact SHA, fresh consent and NEW private output required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0])); var plan=authorized(bytes,args[1],args[2]);
        Path output=Path.of(args[3]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory; no resume/retry");
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var budget=new TextBoundaryTrial.Budget(1,.10,.50,output);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(45),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        // Reuse the exact-body guard, with this trial's stricter 24-attempt ceiling.
        var guard=new ContextRoutingProbeExecution.Guard(budget);
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(45));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((req,body,exec) -> { check(budget.attempts<24,"24-attempt ceiling"); guard.before(body,req.getURI().toString()); return exec.execute(req,body); });
        if(props.hasOrganization()) rest.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) rest.defaultHeader("OpenAI-Project",props.project());
        var client=new OpenAiClient(rest.build(),props);
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=0; save(output,args[1],rows,budget,cost,"RUNNING");
        for(var task:plan.tasks()) {
            client.beginVideo(0L); client.beginAnalyzer("semantic-reference-rank-trial");
            guard.arm(task.system(),task.input(),client); int before=budget.attempts; long start=System.nanoTime();
            var response=client.completeRoutingAsJson(task.system(),task.input(),ContextSemanticRankPreparation.Response.class).orElse(null);
            var usage=client.videoUsage(); cost+=usage.costUsd();
            String failure=ContextSemanticRankPreparation.failure(response,task.localIdMap().keySet());
            List<String> top=failure==null && !response.abstain()?response.groups().get(0):List.of();
            var row=new LinkedHashMap<String,Object>(); row.put("id",task.id()); row.put("sourceRequestKey",task.sourceRequestKey());
            row.put("queryKind",task.queryKind()); row.put("order",task.order()); row.put("response",response); row.put("failure",failure);
            row.put("inputSha256",task.inputSha256()); row.put("elapsedMs",(System.nanoTime()-start)/1_000_000); row.put("usage",usage);
            boolean includes=task.expectedTargetForDevelopmentOnly()!=null && top.contains(task.expectedTargetForDevelopmentOnly());
            row.put("strictFirst",top.size()==1 && includes);
            row.put("topIncludesTarget",includes); rows.add(row);
            boolean ok=response!=null && usage.calls()==1 && budget.attempts==before+1 && !budget.exhausted && cost<=1;
            save(output,args[1],rows,budget,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE");
            System.out.println("SEMANTIC_RANK "+rows.size()+"/24 "+task.id()+" valid="+(failure==null));
            client.endVideo(); check(ok,"Stopped; retain partial results without retry");
        }
        save(output,args[1],rows,budget,cost,"COMPLETE");
        System.out.println("SEMANTIC_RANK_COMPLETE "+metrics(rows)+" usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
    }
}
