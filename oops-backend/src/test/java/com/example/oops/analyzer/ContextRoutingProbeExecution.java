package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
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

/** Explicitly authorized router-only trial. No discovery, card verdicts, repair or automatic experiment retry. */
public final class ContextRoutingProbeExecution {
    static final String CONSENT="NEW_USD_1_SYNTHETIC_ROUTING_TEXT_EGRESS_APPROVED";
    static final String SCHEMA="context-routing-probe-results-1";
    static final double INPUT=.10,CACHED=.01,OUTPUT=.50;
    static final int MAX_REQUESTS=48;
    static final class Guard {
        final TextBoundaryTrial.Budget budget;
        private JsonNode expected; private boolean armed;
        Guard(TextBoundaryTrial.Budget budget) { this.budget=budget; }
        void arm(ContextRoutingProbePlan.Task task,OpenAiClient client) {
            arm(task.system(),task.input(),client);
        }
        void arm(String system,String input,OpenAiClient client) {
            var body=new LinkedHashMap<>(client.replayRequestBody(system,input));
            body.put("max_completion_tokens",1024);
            expected=JSON.valueToTree(body); armed=true;
        }
        void before(byte[] body,String uri) throws java.io.IOException {
            check(armed && budget.attempts<MAX_REQUESTS,"Unarmed/repeated request or 48-request limit");
            check("https://api.openai.com/v1/chat/completions".equals(uri),"Only approved OpenAI endpoint allowed");
            check(expected.equals(JSON.readTree(body)),"HTTP body differs from frozen logical routing request");
            check("gpt-6-luna".equals(expected.path("model").asText())
                    && expected.path("max_completion_tokens").asInt()==1024
                    && "none".equals(expected.path("reasoning_effort").asText()),"Frozen model/reasoning/output cap required");
            budget.reserve(body.length); // Existing conservative reservation uses 8000 output tokens, not the smaller wire cap.
            armed=false;
        }
    }
    record Summary(String kind,int requests,int validResponses,int expectedKeyProposed,int expectedKeyPassed,
                   int beforeSelected,int afterSelected,int afterFirst) {}
    static List<Summary> summarize(List<Map<String,Object>> rows) {
        var groups=new TreeMap<String,List<Map<String,Object>>>();
        rows.forEach(r -> groups.computeIfAbsent((String)r.get("kind"),k -> new ArrayList<>()).add(r));
        return groups.entrySet().stream().map(g -> {
            var outcomes=g.getValue().stream().map(r -> (ContextRoutingProbePlan.Outcome)r.get("outcome")).toList();
            return new Summary(g.getKey(),outcomes.size(),(int)outcomes.stream().filter(o -> Set.of("ROUTED","EMPTY").contains(o.router().state())).count(),
                    (int)outcomes.stream().filter(ContextRoutingProbePlan.Outcome::expectedKeyProposed).count(),
                    (int)outcomes.stream().filter(ContextRoutingProbePlan.Outcome::expectedKeyPassed).count(),
                    (int)outcomes.stream().filter(o -> o.beforeSelectedRank()>0).count(),
                    (int)outcomes.stream().filter(o -> o.afterSelectedRank()>0).count(),
                    (int)outcomes.stream().filter(o -> o.afterSelectedRank()==1).count());
        }).toList();
    }
    static ContextRoutingProbePlan.Plan authorized(byte[] bytes,String sha,String consent) throws Exception {
        check(ReferenceOnlyTrial.sha(bytes).equals(sha) && CONSENT.equals(consent),"Fresh exact-plan cost/text consent required");
        var p=JSON.readValue(bytes,ContextRoutingProbePlan.Plan.class); ContextRoutingProbePlan.validate(p);
        check(p.tasks().size()==MAX_REQUESTS,"Exactly the approved 48 requests required"); return p;
    }
    static void save(Path output,String planSha,List<Map<String,Object>> rows,Guard guard,double cost,String state) throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion",SCHEMA); report.put("planSha256",planSha); report.put("state",state);
        report.put("authorization","NEW_EXPLICIT_USD_1_AND_SYNTHETIC_TEXT_EGRESS"); report.put("approvedEstimatedUsd",1);
        report.put("reservedEstimatedUsd",guard.budget.reserved); report.put("httpAttempts",guard.budget.attempts);
        report.put("usageEstimatedUsd",cost); report.put("actualBillingConfirmed",false);
        report.put("pricingBasis","GPT_6_LUNA_STANDARD_2026_10_15_INPUT_0.10_CACHED_0.01_OUTPUT_0.50_PER_1M");
        report.put("reservationOutputTokens",8000); report.put("wireOutputCap",1024);
        report.put("labelStatus","SYNTHETIC_AUTHOR_PROPOSALS_NOT_APPROVED_GOLD");
        report.put("normalRoutingIsNotFalsePositive",true); report.put("verdictAccuracyMeasured",false);
        report.put("summary",summarize(rows)); report.put("results",rows);
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(report));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"Frozen plan, approved plan SHA, fresh consent, NEW private result directory required");
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private result directory required; no resume/retry");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0])); var p=authorized(bytes,args[1],args[2]);
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required (never print it)");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",p.plannedModel(),"none",Duration.ofSeconds(45),new OpenAiProperties.Pricing(INPUT,CACHED,OUTPUT,null,null));
        var guard=new Guard(new TextBoundaryTrial.Budget(1,INPUT,OUTPUT,output));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(45));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    guard.before(body,request.getURI().toString()); return execution.execute(request,body);
                });
        if(props.hasOrganization()) rest.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) rest.defaultHeader("OpenAI-Project",props.project());
        var client=new OpenAiClient(rest.build(),props);
        var library=ContextRoutingProbePlan.library(p.runtimeText());
        var source=JSON.readValue(p.probesText(),ContextRetrievalContrastAudit.Plan.class);
        Map<String,ContextRetrievalContrastAudit.Probe> probes=new HashMap<>(); source.probes().forEach(q -> probes.put(q.id(),q));
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=0;
        save(output,args[1],rows,guard,cost,"RUNNING");
        // Interleave probes within a repetition instead of sending three adjacent copies of each probe.
        var tasks=p.tasks().stream().sorted(Comparator.comparingInt(ContextRoutingProbePlan.Task::repeat)
                .thenComparing(ContextRoutingProbePlan.Task::probeId)).toList();
        for(var task:tasks) {
            client.beginVideo(0L); client.beginAnalyzer("routing-paraphrase-probe");
            guard.arm(task,client); int before=guard.budget.attempts; long start=System.nanoTime();
            var response=client.completeRoutingAsJson(task.system(),task.input(),ContextMechanismRouter.Response.class).orElse(null);
            var usage=client.videoUsage(); cost+=usage.costUsd();
            var q=probes.get(task.probeId()); var outcome=ContextRoutingProbePlan.replay(q,library,p.registryText(),response);
            var row=new LinkedHashMap<String,Object>(); row.put("requestKey",task.requestKey()); row.put("probeId",task.probeId());
            row.put("kind",q.kind()); row.put("repeat",task.repeat()); row.put("inputSha256",task.inputSha256());
            row.put("elapsedMs",(System.nanoTime()-start)/1_000_000); row.put("response",response); row.put("usage",usage); row.put("outcome",outcome);
            rows.add(row);
            boolean ok=response!=null && usage.calls()==1 && guard.budget.attempts==before+1 && !guard.budget.exhausted && cost<=1;
            save(output,args[1],rows,guard,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE");
            System.out.println("ROUTING_PROBE "+rows.size()+"/48 "+task.requestKey()+" state="+outcome.router().state()
                    +" keyPassed="+outcome.expectedKeyPassed()+" referenceRank="+outcome.afterSelectedRank());
            client.endVideo(); check(ok,"Stopped: retain partial results; no automatic retry or replacement requests");
        }
        check(guard.budget.attempts==48,"Exactly 48 HTTP attempts required");
        save(output,args[1],rows,guard,cost,"COMPLETE");
        summarize(rows).forEach(s -> System.out.println("ROUTING_SUMMARY "+s));
        System.out.println("ROUTING_PROBE_COMPLETE usageEstimatedUsd="+cost+" reservedEstimatedUsd="+guard.budget.reserved);
    }
}
