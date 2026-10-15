package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Approved v1/v2 stored STT trial. Router alone varies; no media, search, roles, rerank, DB or deployment. */
public final class V7RoutingVideoTrial {
    static final String CONSENT="NEW_USD1_V1_V2_STORED_TEXT_ROUTING_APPROVED_20261016";
    record Plan(String schemaVersion,String model,int repeats,double estimatedCap,boolean sourceRelationsEnabled,
                String v1SourceSha256,String v2SourceSha256,String runtimeText,String runtimeSha256,
                String registryText,String registrySha256,Map<String,String> implementationSha256,
                List<MechanismRoutingTrial.Condition> conditions) {}
    static Map<String,String> implementation() throws Exception {
        Map<String,String> hashes=new TreeMap<>();
        try(var files=Files.list(Path.of("src/main/java/com/example/oops/analyzer"))) {
            for(var path:files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                hashes.put(path.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        }
        for(String name:List.of("src/main/java/com/example/oops/client/OpenAiClient.java",
                "src/test/java/com/example/oops/analyzer/V7RoutingVideoTrial.java",
                "src/test/java/com/example/oops/analyzer/MechanismRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/TextBoundaryTrial.java"))
            hashes.put(name,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(name))));
        return Map.copyOf(hashes);
    }
    static Plan prepare(byte[] v1,byte[] v2,byte[] runtime,byte[] registry) throws Exception {
        var first=JSON.readTree(v1); check(first.path("transcript").isArray(),"Stored v1 STT required");
        List<ReviewInput.Segment> lines=new ArrayList<>(); int i=0;
        for(var row:first.path("transcript")) lines.add(new ReviewInput.Segment("stt-index-"+i++,TimelineEventType.SPEECH,
                row.path("startMs").asLong(),row.path("endMs").asLong(),row.path("text").asText(),null));
        var second=JSON.readValue(v2,MechanismRoutingTrial.Plan.class);
        var matches=second.conditions().stream().filter(c -> c.id().equals("65-product-review")).toList();
        check(matches.size()==1,"Exactly one saved v2 product transcript required");
        var p=new Plan("v7-routing-video-trial-1","gpt-6-luna",2,1,false,
                ReferenceOnlyTrial.sha(v1),ReferenceOnlyTrial.sha(v2),new String(runtime,StandardCharsets.UTF_8),ReferenceOnlyTrial.sha(runtime),
                new String(registry,StandardCharsets.UTF_8),ReferenceOnlyTrial.sha(registry),implementation(),
                List.of(new MechanismRoutingTrial.Condition("v1","KNOWN_INCIDENT_IN_DICTIONARY_DEVELOPMENT_NOT_HOLDOUT",List.copyOf(lines)),
                        new MechanismRoutingTrial.Condition("v2","USER_NORMAL_PRODUCT_REVIEW_DEVELOPMENT",matches.get(0).raw())));
        validate(p); return p;
    }
    static ReviewGuidelineLibrary library(Plan p) {
        return ContextRankReadinessAudit.library(p.runtimeText()); // Current defaults: count3, total6000, examples3000.
    }
    static void validate(Plan p) throws Exception {
        check("v7-routing-video-trial-1".equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model())
                && p.repeats()==2 && p.estimatedCap()==1 && !p.sourceRelationsEnabled()
                && p.conditions().stream().map(MechanismRoutingTrial.Condition::id).toList().equals(List.of("v1","v2")),"Frozen approved scope required");
        check(p.runtimeSha256().equals(ReferenceOnlyTrial.sha(p.runtimeText().getBytes(StandardCharsets.UTF_8)))
                && p.registrySha256().equals(ReferenceOnlyTrial.sha(p.registryText().getBytes(StandardCharsets.UTF_8)))
                && p.implementationSha256().equals(implementation()),"Source/runtime/registry changed");
        check(JSON.readTree(p.runtimeText()).path("schemaVersion").asText().equals("review-guidelines-7")
                && library(p).relationArchive(),"Valid unexpired v7 required");
        for(var c:p.conditions()) {
            check(!c.raw().isEmpty() && c.raw().size()<=64 && c.raw().stream().allMatch(s -> s.type()==TimelineEventType.SPEECH),"Bounded speech-only snapshot required");
            new ReviewInput(c.raw());
        }
    }
    static Plan authorized(byte[] bytes,String sha,String consent) throws Exception {
        check(CONSENT.equals(consent) && ReferenceOnlyTrial.sha(bytes).equals(sha),"Fresh exact-plan USD1/text consent required");
        var p=JSON.readValue(bytes,Plan.class); validate(p); return p;
    }
    static byte[] guardBody(byte[] body,String uri) {
        check(uri.equals("https://api.openai.com/v1/chat/completions"),"Only approved OpenAI endpoint allowed");
        var json=JSON.readTree(body);
        check(json.path("model").asText().equals("gpt-6-luna") && json.path("reasoning_effort").asText().equals("none")
                && !json.has("tools") && json.path("messages").isArray(),"Frozen model/text request required");
        for(var message:json.path("messages")) check(message.path("content").isString(),"No image/audio input allowed");
        return MechanismRoutingTrial.boundedBody(body);
    }
    static void save(Path output,Plan p,String sha,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("schemaVersion",p.schemaVersion()); report.put("state",state); report.put("planSha256",sha);
        report.put("authorization",CONSENT); report.put("approvedEstimatedUsd",1); report.put("httpAttempts",budget.attempts);
        report.put("reservedEstimatedUsd",budget.reserved); report.put("usageEstimatedUsd",cost); report.put("actualBillingConfirmed",false);
        report.put("pricingBasis","CONFIGURED_USD_PER_1M_INPUT_0.10_CACHED_0.01_OUTPUT_0.50_NOT_CONFIRMED_BILLING");
        report.put("scope","STORED_FULL_STT_TEXT_ENGINE_NOT_MEDIA_OCR_FUSION_OR_DB");
        report.put("sourceRelationsEnabled",false); report.put("runtimeSchema","review-guidelines-7");
        report.put("results",rows); Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(report));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==6 && Set.of("prepare","run").contains(args[0]),"prepare v1-source v2-plan runtime registry NEW-dir OR run plan sha consent unused NEW-dir");
        Path output=Path.of(args[5]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required; no resume/repeated experiment");
        if(args[0].equals("prepare")) {
            var p=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[1])),OfflineDecisionContractAudit.bounded(Path.of(args[2])),
                    OfflineDecisionContractAudit.bounded(Path.of(args[3])),OfflineDecisionContractAudit.bounded(Path.of(args[4])));
            Files.createDirectory(output); Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(p),StandardOpenOption.CREATE_NEW);
            for(var c:p.conditions()) System.out.println("V7_ROUTING_PREPARED "+c.id()+" lines="+c.raw().size()+" batches="
                    +TextReviewBatchPlanner.plan(MechanismRoutingTrial.context(c).reviewInput(),TimelineEventType.SPEECH,3).size());
            System.out.println("V7_ROUTING_PREPARED analyses=8 modelCalls=0 cap=1 maxHttpAttempts=80 roles=false"); return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1])); var p=authorized(bytes,args[2],args[3]);
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var budget=new TextBoundaryTrial.Budget(1,.10,.50,output);
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] bounded=guardBody(body,request.getURI().toString()); budget.reserve(bounded.length);
                    request.getHeaders().setContentLength(bounded.length); return execution.execute(request,bounded);
                });
        if(props.hasOrganization()) rest.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) rest.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(rest.build(),props); var library=library(p);
        var router=new ContextMechanismRouter(client,MechanismRoutingTrial.resources(p.registryText()),true,"classpath:frozen.json",12); router.load();
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=0; save(output,p,args[2],rows,budget,cost,"RUNNING");
        for(int repeat=1;repeat<=2;repeat++) for(var c:p.conditions()) for(String arm:repeat==1?List.of("OFF","ON"):List.of("ON","OFF")) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("v7-v1-v2-routing-trial"); long start=System.nanoTime();
            var result=CandidateReviewEngine.run(client,MechanismRoutingTrial.context(c),24,null,null,library,null,0,false,arm.equals("ON")?router:null);
            var usage=client.videoUsage(); cost+=usage.costUsd();
            var row=new LinkedHashMap<String,Object>(); row.put("caseId",c.id()); row.put("labelStatus",c.labelStatus()); row.put("arm",arm); row.put("repeat",repeat);
            row.put("elapsedMs",(System.nanoTime()-start)/1_000_000); row.put("status",result.status()); row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations()); row.put("unassessedSegmentIds",result.unassessedSegmentIds());
            row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
            row.put("findings",result.findings().stream().map(f -> Map.of("category",f.getCategory(),"text",f.primaryText(),"reason",f.getReason(),
                    "target",f.getTarget()==null?"":f.getTarget(),"supports",f.validatedSupports())).toList()); rows.add(row);
            boolean ok=!budget.exhausted && cost<=1 && client.failureCount()==0 && usage.calls()==client.calls.size();
            save(output,p,args[2],rows,budget,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE");
            System.out.println("V7_ROUTING "+rows.size()+"/8 "+c.id()+" "+arm+" repeat="+repeat+" findings="+result.findings().size()+" status="+result.status()+" calls="+usage.calls());
            client.endVideo(); check(ok,"Stopped; keep partial results without rerunning experiment");
        }
        save(output,p,args[2],rows,budget,cost,"COMPLETE");
        System.out.println("V7_ROUTING_COMPLETE analyses="+rows.size()+" http="+budget.attempts+" usageEstimatedUsd="+cost+" reserved="+budget.reserved);
    }
}
