package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** New USD3 scope only. Read-only DB snapshot then text-only v7 routing comparison. */
public final class LongVideoRoutingTrial {
    static final String CONSENT="NEW_USD3_V4_V6_STORED_TEXT_ROUTING_APPROVED_20261016";
    record Source(String caseId,long videoId,String filename,int durationSec,String labelStatus,
                  List<ReviewInput.Segment> raw,String rawSha256) {}
    record Plan(String schemaVersion,String model,int repeats,double estimatedCap,int maxHttpAttempts,int maxRouterCalls,
                boolean sourceRelationsEnabled,String runtimeText,String runtimeSha256,String registryText,String registrySha256,
                Map<String,String> implementationSha256,List<Source> sources) {}
    static final class Budget {
        final Path output; double reserved; int attempts; boolean exhausted;
        Budget(Path output) { this.output=output; }
        void reserve(int bytes) throws Exception {
            check(bytes>=0,"Invalid request size");
            double next=(bytes+1024)*.10/1_000_000+8000*.50/1_000_000;
            if(attempts>=400 || reserved+next>3) { exhausted=true; throw new IllegalArgumentException("USD3/400-request reservation exhausted"); }
            reserved+=next; attempts++;
            Files.writeString(output.resolve("reservation.json"),JSON.writeValueAsString(Map.of("approvedEstimatedUsd",3,
                    "reservedEstimatedUsd",reserved,"httpAttempts",attempts,"maxHttpAttempts",400,"reservationOutputTokens",8000,
                    "actualBillingConfirmed",false)));
        }
    }
    static Map<String,String> implementation() throws Exception {
        var hashes=new TreeMap<>(V7RoutingVideoTrial.implementation());
        for(String name:List.of("src/test/java/com/example/oops/analyzer/LongVideoRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/ContextRankReadinessAudit.java"))
            hashes.put(name,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(name))));
        return Map.copyOf(hashes);
    }
    static List<Source> sources(Connection connection) throws Exception {
        List<Source> sources=new ArrayList<>();
        for(var entry:List.of(Map.entry("v4",67L),Map.entry("v6",69L))) {
            String filename; int duration;
            try(var q=connection.prepareStatement("SELECT filename,duration_sec FROM video WHERE id=?")) {
                q.setLong(1,entry.getValue()); try(var r=q.executeQuery()) {
                    check(r.next(),"Required saved video missing"); filename=r.getString(1); duration=r.getInt(2);
                    check(filename.equals(entry.getKey()+".mp4") && duration>0 && !r.next(),"Video identity mismatch");
                }
            }
            List<ReviewInput.Segment> lines=new ArrayList<>();
            try(var q=connection.prepareStatement("SELECT id,start_ms,end_ms,text FROM transcript_segment WHERE video_id=? ORDER BY start_ms,end_ms,id")) {
                q.setLong(1,entry.getValue()); try(var r=q.executeQuery()) {
                    while(r.next()) lines.add(new ReviewInput.Segment("stt-"+r.getLong(1),TimelineEventType.SPEECH,r.getLong(2),r.getLong(3),r.getString(4),null));
                }
            }
            check(!lines.isEmpty() && lines.size()<=512,"Bounded complete stored transcript required");
            var raw=new ReviewInput(lines).segments();
            sources.add(new Source(entry.getKey(),entry.getValue(),filename,duration,"PRIOR_WARNINGS_NOT_ADJUDICATED_NOT_NORMAL_GOLD",raw,
                    ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(raw))));
        }
        return List.copyOf(sources);
    }
    static Plan prepare(List<Source> sources,byte[] runtime,byte[] registry) throws Exception {
        var p=new Plan("long-video-routing-trial-1","gpt-6-luna",2,3,400,12,false,
                new String(runtime,StandardCharsets.UTF_8),ReferenceOnlyTrial.sha(runtime),new String(registry,StandardCharsets.UTF_8),
                ReferenceOnlyTrial.sha(registry),implementation(),sources); validate(p); return p;
    }
    static void validate(Plan p) throws Exception {
        check("long-video-routing-trial-1".equals(p.schemaVersion()) && p.model().equals("gpt-6-luna") && p.repeats()==2
                && p.estimatedCap()==3 && p.maxHttpAttempts()==400 && p.maxRouterCalls()==12 && !p.sourceRelationsEnabled()
                && p.sources().stream().map(Source::caseId).toList().equals(List.of("v4","v6")),"Frozen USD3/v4/v6 scope required");
        check(p.implementationSha256().equals(implementation())
                && p.runtimeSha256().equals(ReferenceOnlyTrial.sha(p.runtimeText().getBytes(StandardCharsets.UTF_8)))
                && p.registrySha256().equals(ReferenceOnlyTrial.sha(p.registryText().getBytes(StandardCharsets.UTF_8))),"Frozen implementation/archive changed");
        check(JSON.readTree(p.runtimeText()).path("schemaVersion").asText().equals("review-guidelines-7")
                && ContextRankReadinessAudit.library(p.runtimeText()).relationArchive(),"Ready unexpired v7 required");
        for(var s:p.sources()) check(s.videoId()==(s.caseId().equals("v4")?67L:69L) && s.filename().equals(s.caseId()+".mp4")
                && !s.raw().isEmpty() && s.raw().size()<=512 && s.raw().stream().allMatch(r -> r.type()==TimelineEventType.SPEECH)
                && s.rawSha256().equals(ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(s.raw())))
                && new ReviewInput(s.raw()).segments().equals(s.raw()),"Transcript identity/order/hash changed");
    }
    static Plan authorized(byte[] bytes,String sha,String consent) throws Exception {
        check(CONSENT.equals(consent) && sha.equals(ReferenceOnlyTrial.sha(bytes)),"Fresh exact-plan USD3/text consent required");
        var p=JSON.readValue(bytes,Plan.class); validate(p); return p;
    }
    static void save(Path output,Plan p,String sha,List<Map<String,Object>> rows,Budget budget,double cost,String state) throws Exception {
        var report=new LinkedHashMap<String,Object>(); report.put("schemaVersion",p.schemaVersion()); report.put("state",state);
        report.put("planSha256",sha); report.put("authorization",CONSENT); report.put("approvedEstimatedUsd",3);
        report.put("reservedEstimatedUsd",budget.reserved); report.put("httpAttempts",budget.attempts); report.put("maxHttpAttempts",400);
        report.put("usageEstimatedUsd",cost); report.put("actualBillingConfirmed",false);
        report.put("pricingBasis","CONFIGURED_USD_PER_1M_INPUT_0.10_CACHED_0.01_OUTPUT_0.50_NOT_CONFIRMED_BILLING");
        report.put("scope","COMPLETE_STORED_STT_TEXT_ENGINE_NOT_MEDIA_OCR_FUSION_OR_DB_WRITES"); report.put("results",rows);
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(report));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==5 && Set.of("prepare","run").contains(args[0]),"prepare db runtime registry NEW-dir OR run plan sha consent NEW-dir");
        Path output=Path.of(args[4]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory only; no resume or replacement experiment");
        if(args[0].equals("prepare")) {
            Path database=Path.of(args[1]).toRealPath();
            check(database.equals(Path.of("data/oops.mv.db").toRealPath()),"Only scoped local DB allowed");
            String path=database.toString(); String url="jdbc:h2:file:"+path.substring(0,path.length()-6)+";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
            List<Source> saved; Class.forName("org.h2.Driver");
            try(var connection=DriverManager.getConnection(url,"sa","")) {
                connection.setReadOnly(true); connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                saved=sources(connection); connection.rollback();
            }
            var p=prepare(saved,OfflineDecisionContractAudit.bounded(Path.of(args[2])),OfflineDecisionContractAudit.bounded(Path.of(args[3])));
            Files.createDirectory(output); Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(p),StandardOpenOption.CREATE_NEW);
            for(var s:saved) System.out.println("LONG_ROUTING_PREPARED "+s.caseId()+" lines="+s.raw().size()+" batches="
                    +TextReviewBatchPlanner.plan(new ReviewInput(s.raw()),TimelineEventType.SPEECH,3).size());
            System.out.println("LONG_ROUTING_PREPARED analyses=8 providerCalls=0 cap=3 maxHttpAttempts=400 routerCap=12"); return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1])); var p=authorized(bytes,args[2],args[3]);
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output); Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW); var budget=new Budget(output);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),"https://api.openai.com/v1",p.model(),
                "none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] bounded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { budget.reserve(bounded.length); } catch(java.io.IOException|RuntimeException e) { throw e; }
                    catch(Exception e) { throw new java.io.IOException("Reservation persistence failed",e); }
                    request.getHeaders().setContentLength(bounded.length); return execution.execute(request,bounded);
                });
        if(props.hasOrganization()) rest.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) rest.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(rest.build(),props); var library=ContextRankReadinessAudit.library(p.runtimeText());
        var router=new ContextMechanismRouter(client,MechanismRoutingTrial.resources(p.registryText()),true,"classpath:frozen.json",p.maxRouterCalls()); router.load();
        List<Map<String,Object>> rows=new ArrayList<>(); double cost=0; save(output,p,args[2],rows,budget,cost,"RUNNING");
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
            save(output,p,args[2],rows,budget,cost,ok?"RUNNING":"STOPPED_PROVIDER_BUDGET_OR_USAGE_FAILURE");
            System.out.println("LONG_ROUTING "+rows.size()+"/8 "+s.caseId()+" "+arm+" repeat="+repeat+" findings="+result.findings().size()+" status="+result.status()+" calls="+usage.calls());
            client.endVideo(); check(ok,"Stopped; retain partial results without rerunning experiment");
        }
        save(output,p,args[2],rows,budget,cost,"COMPLETE");
        System.out.println("LONG_ROUTING_COMPLETE analyses=8 http="+budget.attempts+" usageEstimatedUsd="+cost+" reserved="+budget.reserved);
    }
}
