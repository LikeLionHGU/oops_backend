package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.core.io.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Opt-in real discovery/routing/verification on frozen STT excerpts, not full video or fixed candidates. */
public final class MechanismRoutingTrial {
    static final String SCHEMA = "mechanism-routing-trial-1";
    static final List<String> IDS = List.of("64-region","64-graphic","65-product-review","66-banter");
    record Condition(String id, String labelStatus, List<ReviewInput.Segment> raw) {}
    record Plan(String schemaVersion, String scope, int repeats, double approvedEstimatedUsd,
                String model, String sourcePlanSha256, String runtimeText, String runtimeSha256,
                String registryText, String registrySha256, List<Condition> conditions) {}
    static Plan prepare(byte[] source, byte[] runtime, byte[] registry) throws Exception {
        var tree = JSON.readTree(source);
        List<Condition> cases = new ArrayList<>();
        for (String id : IDS) {
            List<JsonNode> matches = new ArrayList<>();
            tree.path("tasks").forEach(t -> { if (id.equals(t.path("spec").path("id").asText())) matches.add(t); });
            check(matches.size() == 1,"One stored excerpt per condition required");
            var input = JSON.readTree(matches.get(0).path("input").asText());
            var raw = CandidateHypothesisTrial.raw(input).segments();
            check(!raw.isEmpty() && raw.size() <= 48,"Bounded frozen excerpt required");
            String status = id.equals("66-banter") ? "VIDEO_FEEDBACK_NORMAL_TEXT_SUFFICIENCY_UNREVIEWED"
                    : "USER_REVIEWED_DEVELOPMENT_FLOW";
            cases.add(new Condition(id,status,raw));
        }
        var p = new Plan(SCHEMA,"STORED_STT_EXCERPTS_LIVE_DISCOVERY_AND_VERIFICATION_NOT_FULL_VIDEO",2,1,
                "gpt-6-luna",ReferenceOnlyTrial.sha(source),new String(runtime,StandardCharsets.UTF_8),ReferenceOnlyTrial.sha(runtime),
                new String(registry,StandardCharsets.UTF_8),ReferenceOnlyTrial.sha(registry),List.copyOf(cases));
        validate(p); return p;
    }
    static void validate(Plan p) throws Exception {
        check(SCHEMA.equals(p.schemaVersion()) && p.repeats() == 2 && p.approvedEstimatedUsd() == 1
                && p.model().equals("gpt-6-luna") && p.conditions().stream().map(Condition::id).toList().equals(IDS),"Known frozen plan required");
        check(ReferenceOnlyTrial.sha(p.runtimeText().getBytes(StandardCharsets.UTF_8)).equals(p.runtimeSha256())
                && ReferenceOnlyTrial.sha(p.registryText().getBytes(StandardCharsets.UTF_8)).equals(p.registrySha256()),"Snapshot hash mismatch");
        check(library(p).contextUnitArchive(),"Valid unexpired v6 archive required");
        for (var c : p.conditions()) {
            check(!c.raw().isEmpty() && c.raw().size() <= 48 && c.raw().stream().allMatch(s -> s.type()==TimelineEventType.SPEECH),"Speech excerpt required");
            new ReviewInput(c.raw());
        }
    }
    static ResourceLoader resources(String text) {
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                check(location.equals("classpath:frozen.json"),"Only embedded frozen resource allowed");
                return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
    static ReviewGuidelineLibrary library(Plan p) {
        var l = new ReviewGuidelineLibrary(resources(p.runtimeText()),true,"classpath:frozen.json",6000,2,3000,"");
        l.load(); return l;
    }
    static AnalysisContext context(Condition c) {
        return new AnalysisContext(Video.builder().filename("private-frozen-stt-excerpt").build(),ContentGenre.GENERAL,
                List.of(),List.of(),new ReviewInput(c.raw()));
    }
    static final class Client extends OpenAiClient {
        final List<Map<String,Object>> calls = new ArrayList<>();
        Client(RestClient rest, OpenAiProperties properties) { super(rest,properties); }
        private <T> Optional<T> record(String kind, String system, String input, Optional<T> output) {
            var row = new LinkedHashMap<String,Object>();
            row.put("kind",kind); row.put("system",system); row.put("input",JSON.readTree(input)); row.put("output",output.orElse(null));
            row.put("failureCode",failureCode().orElse(null)); calls.add(row); return output;
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type) {
            return record(type.getSimpleName(),system,input,super.completeAsJson(system,input,type));
        }
        @Override public <T> Optional<T> completeRoutingAsJson(String system,String input,Class<T> type) {
            return record("MECHANISM_ROUTING",system,input,super.completeRoutingAsJson(system,input,type));
        }
    }
    static byte[] boundedBody(byte[] body) {
        var tree = JSON.readTree(body);
        int limit = tree.path("max_completion_tokens").asInt(8000);
        ((tools.jackson.databind.node.ObjectNode)tree).put("max_completion_tokens",Math.min(limit,8000));
        return JSON.writeValueAsBytes(tree); // Keep the router's 1024 cap; do not replace it with 8000.
    }
    static Client client(OpenAiProperties props,Budget budget) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build()); factory.setReadTimeout(Duration.ofSeconds(90));
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+props.apiKey()).requestInterceptor((request,body,execution) -> {
                    byte[] bounded = boundedBody(body);
                    budget.reserve(bounded.length); // Persist conservative reservation before every HTTP attempt, including retries.
                    request.getHeaders().setContentLength(bounded.length);
                    return execution.execute(request,bounded);
                });
        if (props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if (props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        return new Client(builder.build(),props);
    }
    static void save(Path output,List<Map<String,Object>> rows,Budget budget,double cost,String state,String sha) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion",SCHEMA,
                "planSha256",sha,"state",state,"results",rows,"usageEstimatedUsd",cost,
                "reservedEstimatedUsd",budget.reserved,"httpAttempts",budget.attempts,
                "approvedEstimatedUsd",1,"actualBillingConfirmed",false)));
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 5 && Set.of("prepare","run").contains(args[0]),
                "prepare source-plan runtime registry new-dir OR run plan approved-sha consent new-dir");
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required");
        if (args[0].equals("prepare")) {
            var p = prepare(OfflineDecisionContractAudit.bounded(Path.of(args[1])),
                    OfflineDecisionContractAudit.bounded(Path.of(args[2])),OfflineDecisionContractAudit.bounded(Path.of(args[3])));
            Files.createDirectory(output);
            Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(p),StandardOpenOption.CREATE_NEW);
            for (var c : p.conditions()) System.out.println("ROUTING_PREPARED "+c.id()+" lines="+c.raw().size()
                    +" batches="+TextReviewBatchPlanner.plan(context(c).reviewInput(),TimelineEventType.SPEECH,3).size());
            System.out.println("ROUTING_PREPARED slots=16 modelCalls=0 maxHttpAttempts=80 estimatedCap=1"); return;
        }
        byte[] bytes = OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        check(ReferenceOnlyTrial.sha(bytes).equals(args[2]) && args[3].equals("TEXT_EGRESS_APPROVED_NEW_USD_1"),"Explicit fresh consent/hash required");
        var p = JSON.readValue(bytes,Plan.class); validate(p);
        String key = System.getenv("OPENAI_API_KEY"); check(key != null && !key.isBlank(),"OPENAI_API_KEY required");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var props = new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var budget = new Budget(1,.10,.50,output); var client = client(props,budget); var library = library(p);
        var router = new ContextMechanismRouter(client,resources(p.registryText()),true,"classpath:frozen.json",12); router.load();
        List<Map<String,Object>> results = new ArrayList<>(); double cost = 0;
        save(output,results,budget,cost,"RUNNING",args[2]);
        for (int repeat=1; repeat<=2; repeat++) for (var c : p.conditions())
            for (String arm : repeat==1 ? List.of("OFF","ON") : List.of("ON","OFF")) {
                client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("mechanism-routing-trial");
                long start = System.nanoTime();
                var result = CandidateReviewEngine.run(client,context(c),24,null,null,library,null,0,true,arm.equals("ON")?router:null);
                var usage = client.videoUsage(); cost += usage.costUsd();
                var row = new LinkedHashMap<String,Object>();
                row.put("caseId",c.id()); row.put("labelStatus",c.labelStatus()); row.put("arm",arm); row.put("repeat",repeat);
                row.put("elapsedMs",(System.nanoTime()-start)/1_000_000); row.put("status",result.status());
                row.put("diagnostics",result.diagnostics()); row.put("evaluations",result.evaluations());
                row.put("unassessedSegmentIds",result.unassessedSegmentIds()); row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
                row.put("findings",result.findings().stream().map(f -> Map.of("category",f.getCategory(),"text",f.primaryText(),
                        "reason",f.getReason(),"target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList());
                results.add(row);
                boolean ok = !budget.exhausted && client.failureCount()==0 && usage.calls()==client.calls.size();
                save(output,results,budget,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE",args[2]);
                System.out.println("ROUTING_TRIAL "+results.size()+"/16 "+c.id()+" "+arm+" repeat="+repeat
                        +" findings="+result.findings().size()+" status="+result.status()+" calls="+usage.calls());
                client.endVideo(); check(ok,"Stop; retain partial results without retrying the experiment");
            }
        save(output,results,budget,cost,"COMPLETE",args[2]);
        System.out.println("ROUTING_COMPLETE slots="+results.size()+" estimatedUsd="+cost+" reserved="+budget.reserved);
    }
}
