package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static org.mockito.Mockito.*;

/** Isolated automatic retrieval trial, using only synthetic reference text. */
public final class SyntheticAutoReferenceTrial {
    static final String CONSENT="SYNTHETIC_AUTO_REFERENCE_4_USD050";
    static final int LIMIT=16;
    static final String ID="synthetic-constraint#s1", FAMILY="synthetic-constraint-research";
    static final String SEARCH="월세 교통비 생활비 빠듯 부담 고정비 절약 지출 선택 탓 경제적 제약 조언 개인 책임";
    record Run(String caseId,boolean automatic,Frozen initial) {}
    record Plan(String sourcePath,String sourceSha,String runtimePath,String runtimeSha,Map<String,String> implementation,
                List<Condition> cases,List<Run> runs,double maxReservation) {}
    static Archive isolated(String runtime) {
        var base=JSON.readValue(runtime,Archive.class);
        var cmp=new Comparison("SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT",List.of("합성 대조: 생활비 제약 설명과 그에 대한 반응을 구분한다."),
                List.of(SyntheticReferenceComparison.REFERENCE),List.of("실제 사건이나 실제 발언이 아닌 작성형 합성 참고다."),List.of(),
                List.of("제약을 인정하며 선택지를 함께 살피는 상담은 그 자체로 경고 근거가 아니다."));
        var ex=new ContextExample(ID,FAMILY,base.guidelines().stream().map(Rule::id).toList(),List.of("SPEECH"),cmp,
                new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",SEARCH,retrievalHash(SEARCH)),List.of());
        var sources=List.of(new SourceCase(ID,FAMILY,"UNREVIEWED_DRAFT"));
        var rules=new ArrayList<Rule>();for(var r:base.guidelines()){
            rules.add(new Rule(r.id(),r.axis(),r.channels(),r.condition(),r.normalContrast(),r.requiredEvidence(),r.missingContext(),List.of(ID),r.referenceContexts()));}
        var archive=new Archive(base.schemaVersion(),base.version(),base.status(),base.humanValidated(),base.usage(),base.sourceBundleSha256(),
                base.refreshOrDeleteBy(),sources,rules,base.contextDictionarySha256(),List.of(ex));
        check(ReviewGuidelineLibrary.valid(archive),"Invalid isolated archive");return archive;
    }
    static ReviewGuidelineLibrary library(String runtime,boolean automatic)throws Exception {
        if(!automatic)return ConstraintBaselineTrial.baseOnly(runtime);
        var archive=isolated(runtime);var catalog=JSON.readValue(Files.readAllBytes(Path.of("src/main/resources/context-search-profiles.json")),StructuredContextSearch.Catalog.class);
        var defs=new ArrayList<>(catalog.profiles());defs.add(new StructuredContextSearch.Definition("synthetic-constraint-diagnostic",List.of("insult-mockery"),
                List.of("월세|집세|생활비|고정비|생계|소득|수입|가계|경제","탓|네가|낭비|책임|선택|절약|아끼"),
                List.of("제약|생활|생계|경제","책임|선택|탓|낭비")));
        var altered=new StructuredContextSearch.Catalog("diagnostic-only-not-for-release",defs);
        var constructor=StructuredContextSearch.class.getDeclaredConstructor(StructuredContextSearch.Catalog.class,String.class);constructor.setAccessible(true);
        var proposed=constructor.newInstance(altered,retrievalHash(JSON.writeValueAsString(altered)));
        var lib=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(JSON.writeValueAsString(archive)),true,"classpath:frozen.json",6000,3,3000,"",true,true);
        try(var mock=mockStatic(StructuredContextSearch.class,org.mockito.Mockito.CALLS_REAL_METHODS)){
            mock.when(StructuredContextSearch::load).thenReturn(proposed);lib.load();}
        check(lib.trace(TimelineEventType.SPEECH).state().equals("READY_WORKING_REFERENCE"),"Isolated archive unavailable");return lib;
    }
    static Plan prepare(Path source)throws Exception {
        var base=ConstraintBaselineTrial.prepare(source);String runtime=Files.readString(Path.of(base.runtimePath()));var runs=new ArrayList<Run>();
        for(var c:base.cases())for(boolean auto:List.of(false,true)){
            var lib=library(runtime,auto);var client=new SyntheticReferenceComparison.TrialClient(RestClient.create(),c,false,true,new Guard(),null,"offline-no-key");
            pipeline(client,c,lib);check(client.captured.size()==1,"One discovery required");var f=client.captured.get(0);
            int start=f.system().indexOf("contextReference=");check(start>=0,"Missing reference payload");
            var payload=JSON.readTree(f.system().substring(start+"contextReference=".length()));var refs=payload.path("referenceContexts");
            int expected=auto&&c.id().equals("synthetic-constraint-blame")?1:0;
            check(refs.isArray()&&refs.size()==expected,"Unexpected reference retrieval");
            if(expected==1)check(refs.toString().contains("실제 사건이나 실제 발언이 아닌 작성형 합성 참고"),"Only synthetic reference allowed");
            check(!ResearchCaseAliases.containsIncidentReference(f.system()),"Incident references forbidden");
            runs.add(new Run(c.id(),auto,f));
        }
        var impl=new TreeMap<>(base.implementation());for(String f:List.of("SyntheticReferenceComparison.java","SyntheticAutoReferenceTrial.java")){
            var p=Path.of("src/test/java/com/example/oops/analyzer",f);impl.put(p.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(p)));}
        var catalog=Path.of("src/main/resources/context-search-profiles.json");impl.put(catalog.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(catalog)));
        double worst=LIMIT*reservation(MAX_BYTES);check(worst<=CAP,"Budget cap");
        return new Plan(base.sourcePath(),base.sourceSha(),base.runtimePath(),base.runtimeSha(),impl,base.cases(),List.copyOf(runs),worst);
    }
    static void save(Path out,String sha,String state,Guard guard,List<Map<String,Object>> rows)throws Exception{
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,"attempts",guard.attempts,
                "reservedEstimatedUsd",guard.reserved,"actualBillingConfirmed",false,"scope","ISOLATED_SYNTHETIC_AUTO_NOT_VIDEO_ACCURACY","results",rows)));
    }
    public static void main(String[] args)throws Exception{
        check(args.length==4,"Invalid arguments");Path root=Path.of("../datasets/controversy/experiments").toRealPath(),out=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")){var p=prepare(Path.of(args[1]));byte[] b=JSON.writeValueAsBytes(p);Files.createDirectory(out);
            Files.write(out.resolve("plan.json"),b,StandardOpenOption.CREATE_NEW);System.out.println("AUTO_PREPARED calls=0 reserve="+p.maxReservation()+" sha="+ReferenceOnlyTrial.sha(b));return;}
        check(args[0].equals("run"),"Invalid mode");byte[] b=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(b);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("SYNTHETIC_AUTO_CONSENT")),"Exact consent required");var p=JSON.readValue(b,Plan.class);
        check(JSON.valueToTree(p).equals(JSON.valueToTree(prepare(Path.of(p.sourcePath())))),"Plan drift");String key=System.getenv("OPENAI_API_KEY");
        check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);Files.createDirectory(out);
        var guard=new Guard();var rows=new ArrayList<Map<String,Object>>();String runtime=Files.readString(Path.of(p.runtimePath()));save(out,sha,"RUNNING",guard,rows);
        try{for(var run:p.runs()){
            var c=p.cases().stream().filter(x->x.id().equals(run.caseId())).findFirst().orElseThrow();guard.beginCase();var lib=library(runtime,run.automatic());
            var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                    .requestInterceptor((req,data,execution)->{if(guard.attempts>=LIMIT){guard.blocked=true;throw new IllegalStateException("HTTP cap");}
                        byte[] accepted=guard.accept(data,req.getURI().toString());try{save(out,sha,"RUNNING",guard,rows);}
                        catch(Exception e){guard.blocked=true;throw new IllegalStateException("Persistence",e);}
                        req.getHeaders().setContentLength(accepted.length);return execution.execute(req,accepted);}).build();
            var frozen=new SyntheticReferenceComparison.Run(c.id(),false,run.initial());
            var client=new SyntheticReferenceComparison.TrialClient(rest,c,false,false,guard,frozen,key);client.beginVideo(0L);client.beginAnalyzer("synthetic-auto");
            var result=pipeline(client,c,lib);var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());row.put("automatic",run.automatic());
            row.put("expectedDecision",c.expectedDecision());row.put("status",result.status());row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());rows.add(row);
            boolean ok=!guard.blocked&&client.failureCount()==0&&"SUCCESS".equals(result.status().toString());if(!ok)guard.blocked=true;
            save(out,sha,ok?"RUNNING":"STOPPED",guard,rows);client.endVideo();
            System.out.println("AUTO_RESULT "+c.id()+" automatic="+run.automatic()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider, transport, PARTIAL, or budget failure");
        }save(out,sha,"COMPLETE",guard,rows);}catch(Exception e){save(out,sha,"STOPPED",guard,rows);throw e;}
    }
}
