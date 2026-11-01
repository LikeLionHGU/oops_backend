package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** One-shot synthetic reference experiment; no real case references or media. */
public final class SyntheticReferenceComparison {
    static final String CONSENT="SYNTHETIC_REFERENCE_4_USD050";
    static final String REFERENCE="생활 제약을 무시한 채 어려움의 원인을 개인 선택 탓으로 단정하는지 살피고, 제약을 인정하며 선택지를 묻는 대화와 구분한다.";
    static final String ADDITION="\n[합성 참고: 현재 대화의 증거가 아닌 비교 기준]\n"+REFERENCE;
    static final int LIMIT=16;
    static String trialSystem(String system,boolean reference) {
        String marker="contextReference=";int start=system.indexOf(marker);
        if(start>=0){
            start+=marker.length();int depth=0,end=-1;boolean quoted=false,escaped=false;
            for(int i=start;i<system.length();i++){
                char c=system.charAt(i);
                if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
                else if(c=='"')quoted=true;
                else if(c=='{'||c=='[')depth++;
                else if(c=='}'||c==']'){depth--;if(depth==0){end=i+1;break;}}
            }
            check(end>start,"Bounded context reference required");
            system=system.substring(0,start)+canonical(system.substring(start,end))+system.substring(end);
        }
        return system+(reference?ADDITION:"");
    }
    record Run(String caseId, boolean reference, Frozen initial) {}
    record Plan(String sourcePath,String sourceSha,String runtimePath,String runtimeSha,Map<String,String> implementation,
                List<Condition> cases,List<Run> runs,double maxReservation) {}

    static final class TrialClient extends OpenAiClient {
        final Condition condition; final boolean reference, offline; final Guard guard; final Run frozen;
        final List<Frozen> captured=new ArrayList<>();
        TrialClient(RestClient rest,Condition c,boolean ref,boolean offline,Guard guard,Run frozen,String key) {
            super(rest,props(key),true);this.condition=c;this.reference=ref;this.offline=offline;this.guard=guard;this.frozen=frozen;
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type) {
            system=trialSystem(system,reference); input=canonical(input);
            byte[] request=body(JSON.writeValueAsBytes(replayRequestBody(system,input,type)),ENDPOINT);
            var capture=new Frozen(condition.id(),reference?"SYNTHETIC_REFERENCE":"BASE",system,input,type.getSimpleName(),
                    ReviewGuidelineLibrary.retrievalHash(new String(request,StandardCharsets.UTF_8)));
            captured.add(capture);
            if(offline) {
                if(type==CandidateReviewEngine.Discovery.class)return Optional.of(type.cast(new CandidateReviewEngine.Discovery(
                        condition.raw().stream().map(ReviewInput.Segment::id).toList(),List.of(),List.of(),false)));
                return Optional.empty();
            }
            if(captured.size()==1)check(frozen.initial().equals(capture),"Initial request differs from frozen plan");
            guard.begin(request);
            Optional<T> response=super.completeAsJson(system,input,type);
            if(response.isEmpty()||failureCode().isPresent())guard.blocked=true;
            return response;
        }
    }
    static Plan prepare(Path source)throws Exception {
        var base=ConstraintBaselineTrial.prepare(source);
        String runtime=Files.readString(Path.of(base.runtimePath()));var library=ConstraintBaselineTrial.baseOnly(runtime);
        var runs=new ArrayList<Run>();
        for(var c:base.cases())for(boolean ref:List.of(false,true)) {
            var client=new TrialClient(RestClient.create(),c,ref,true,new Guard(),null,"offline-no-key");
            pipeline(client,c,library);check(client.captured.size()==1,"Expected one initial discovery");
            var f=client.captured.get(0);String system=f.system();int i=system.indexOf("contextReference=");
            check(i>=0,"Missing common criteria");String context=system.substring(i+"contextReference=".length());
            if(ref){check(context.endsWith(ADDITION),"Synthetic reference missing");context=context.substring(0,context.length()-ADDITION.length());}
            var parsed=JSON.readTree(context);check(parsed.path("referenceContexts").isArray()&&parsed.path("referenceContexts").isEmpty(),"Existing examples forbidden");
            check(!ResearchCaseAliases.containsIncidentReference(system),"Incident data forbidden");
            runs.add(new Run(c.id(),ref,f));
        }
        var impl=new TreeMap<>(base.implementation());var me=Path.of("src/test/java/com/example/oops/analyzer/SyntheticReferenceComparison.java");
        impl.put(me.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(me)));
        double max=LIMIT*reservation(MAX_BYTES);check(max<=CAP,"Budget cap");
        return new Plan(base.sourcePath(),base.sourceSha(),base.runtimePath(),base.runtimeSha(),impl,base.cases(),List.copyOf(runs),max);
    }
    static void save(Path out,String sha,String state,Guard guard,List<Map<String,Object>> rows)throws Exception {
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,
                "attempts",guard.attempts,"reservedEstimatedUsd",guard.reserved,"actualBillingConfirmed",false,
                "scope","SYNTHETIC_FORCED_REFERENCE_NOT_RETRIEVAL_OR_VIDEO_ACCURACY","results",rows)));
    }
    public static void main(String[] args)throws Exception {
        check(args.length==4,"prepare source ignored output OR run plan sha output");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath(),out=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")){var plan=prepare(Path.of(args[1]));byte[] b=JSON.writeValueAsBytes(plan);
            Files.createDirectory(out);Files.write(out.resolve("plan.json"),b,StandardOpenOption.CREATE_NEW);
            System.out.println("SYNTHETIC_PREPARED calls=0 maxReservation="+plan.maxReservation()+" sha="+ReferenceOnlyTrial.sha(b));return;}
        check(args[0].equals("run"),"Invalid mode");byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("SYNTHETIC_REFERENCE_CONSENT")),"Exact plan and consent required");
        var plan=JSON.readValue(bytes,Plan.class);check(JSON.valueToTree(plan).equals(JSON.valueToTree(prepare(Path.of(plan.sourcePath())))),"Plan drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);
        Files.createDirectory(out);var guard=new Guard();var rows=new ArrayList<Map<String,Object>>();
        var library=ConstraintBaselineTrial.baseOnly(Files.readString(Path.of(plan.runtimePath())));save(out,sha,"RUNNING",guard,rows);
        try { for(var run:plan.runs()) {
            var c=plan.cases().stream().filter(x->x.id().equals(run.caseId())).findFirst().orElseThrow();guard.beginCase();
            var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                    .requestInterceptor((req,data,execution)->{
                        if(guard.attempts>=LIMIT){guard.blocked=true;throw new IllegalStateException("HTTP cap");}
                        byte[] accepted=guard.accept(data,req.getURI().toString());
                        try{save(out,sha,"RUNNING",guard,rows);}catch(Exception ex){guard.blocked=true;throw new IllegalStateException("Reservation persistence",ex);}
                        req.getHeaders().setContentLength(accepted.length);return execution.execute(req,accepted);
                    }).build();
            var client=new TrialClient(rest,c,run.reference(),false,guard,run,key);client.beginVideo(0L);client.beginAnalyzer("synthetic-reference");
            var result=pipeline(client,c,library);var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());row.put("reference",run.reference());
            row.put("expectedDecision",c.expectedDecision());row.put("status",result.status());row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());rows.add(row);
            boolean ok=!guard.blocked&&client.failureCount()==0;save(out,sha,ok?"RUNNING":"STOPPED",guard,rows);client.endVideo();
            System.out.println("SYNTHETIC_RESULT "+c.id()+" reference="+run.reference()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider, transport, or budget failure");
        } save(out,sha,"COMPLETE",guard,rows);}catch(Exception ex){save(out,sha,"STOPPED",guard,rows);throw ex;}
    }
}
