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

/** Opt-in, bounded development regression with a forced synthetic reference. */
public final class SyntheticReferenceRegression {
    static final int LIMIT=32, PER_CASE=4;
    static final double BUDGET=.60;
    static final String CONSENT="SYNTHETIC_REFERENCE_REGRESSION_8_USD060";
    record Run(String caseId,boolean reference,Frozen initial) {}
    record Plan(String inputsPath,String labelsPath,String runtimePath,String sourceFingerprint,Map<String,String> implementation,
                List<Condition> cases,List<Run> runs,double maxReservation) {}
    static final class TrialGuard {
        int attempts,caseAttempts;double reserved;boolean blocked,sent;byte[] expected;
        void beginCase(){caseAttempts=0;expected=null;sent=false;}
        void begin(byte[] data){check(!blocked,"Trial blocked");expected=body(data,ENDPOINT);sent=false;}
        byte[] accept(byte[] data,String uri) {
            try {
                var accepted=body(data,uri);
                check(!blocked&&expected!=null&&!sent&&JSON.readTree(expected).equals(JSON.readTree(accepted)),"Unplanned request");
                double next=reservation(accepted.length);
                check(attempts<LIMIT&&caseAttempts<PER_CASE&&reserved+next<=BUDGET,"Budget or request cap");
                sent=true;attempts++;caseAttempts++;reserved+=next;return accepted;
            } catch(RuntimeException e){blocked=true;throw e;}
        }
    }
    static final class TrialClient extends OpenAiClient {
        final Condition condition;final boolean reference,offline;final TrialGuard guard;final Run frozen;
        final List<Frozen> captured=new ArrayList<>();
        TrialClient(RestClient rest,Condition c,boolean ref,boolean offline,TrialGuard guard,Run frozen,String key){
            super(rest,props(key),true);this.condition=c;this.reference=ref;this.offline=offline;this.guard=guard;this.frozen=frozen;
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type){
            system=SyntheticReferenceComparison.trialSystem(system,reference);input=canonical(input);
            byte[] req=body(JSON.writeValueAsBytes(replayRequestBody(system,input,type)),ENDPOINT);
            var capture=new Frozen(condition.id(),reference?"SYNTHETIC_REFERENCE":"BASE",system,input,type.getSimpleName(),
                    ReviewGuidelineLibrary.retrievalHash(new String(req,StandardCharsets.UTF_8)));captured.add(capture);
            if(offline){if(type==CandidateReviewEngine.Discovery.class)return Optional.of(type.cast(new CandidateReviewEngine.Discovery(
                    condition.raw().stream().map(ReviewInput.Segment::id).toList(),List.of(),List.of(),false)));return Optional.empty();}
            try {
                if(captured.size()==1)check(frozen.initial().equals(capture),"Frozen request mismatch");
                guard.begin(req);Optional<T> response=super.completeAsJson(system,input,type);
                if(response.isEmpty()||failureCode().isPresent())guard.blocked=true;return response;
            }catch(RuntimeException e){guard.blocked=true;throw e;}
        }
    }
    static Plan prepare(Path inputs,Path labels)throws Exception {
        var source=FixedBoundaryBaselineTrial.prepare(inputs,labels);
        var library=ConstraintBaselineTrial.baseOnly(Files.readString(Path.of(source.runtimePath())));var runs=new ArrayList<Run>();
        var selected=source.cases().stream().filter(c->Set.of("A01","A02","A03","A04","R01-166","R01-178").contains(c.id()))
                .sorted(Comparator.comparingInt(c->c.id().startsWith("A")?0:1)).toList();
        for(var c:selected) {
            var modes=Set.of("R01-166","R01-178").contains(c.id())?List.of(false,true):List.of(true);
            for(boolean ref:modes){var client=new TrialClient(RestClient.create(),c,ref,true,new TrialGuard(),null,"offline-no-key");
                pipeline(client,c,library);check(client.captured.size()==1,"One initial discovery required");var f=client.captured.get(0);
                String sys=f.system();int pos=sys.indexOf("contextReference=");check(pos>=0,"Common criteria required");
                String context=sys.substring(pos+"contextReference=".length());
                if(ref){check(context.endsWith(SyntheticReferenceComparison.ADDITION),"Expected synthetic reference");
                    context=context.substring(0,context.length()-SyntheticReferenceComparison.ADDITION.length());}
                check(JSON.readTree(context).path("referenceContexts").isEmpty(),"Existing reference examples forbidden");
                runs.add(new Run(c.id(),ref,f));}
        }
        check(runs.size()==8,"Expected eight analyses");var impl=new TreeMap<>(source.implementation());
        for(String file:List.of("SyntheticReferenceComparison.java","SyntheticReferenceRegression.java")) {
            var p=Path.of("src/test/java/com/example/oops/analyzer",file);impl.put(p.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(p)));}
        double max=LIMIT*reservation(MAX_BYTES);check(max<=BUDGET,"Reservation cap exceeded");
        return new Plan(inputs.toRealPath().toString(),labels.toRealPath().toString(),source.runtimePath(),
                ReferenceOnlyTrial.sha(JSON.writeValueAsBytes(source)),impl,source.cases(),List.copyOf(runs),max);
    }
    static void save(Path out,String sha,String state,TrialGuard guard,List<Map<String,Object>> rows)throws Exception {
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,
                "attempts",guard.attempts,"reservedEstimatedUsd",guard.reserved,"actualBillingConfirmed",false,
                "scope","DEVELOPMENT_FORCED_SYNTHETIC_NOT_AUTOMATIC_RETRIEVAL","results",rows)));
    }
    static boolean acceptResult(TrialGuard guard,String status,long failures){
        if(guard.blocked||failures!=0||!"SUCCESS".equals(status)){guard.blocked=true;return false;}
        return true;
    }
    public static void main(String[] args)throws Exception {
        check(args.length==4,"Invalid arguments");Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path out=Path.of(args[3]).toAbsolutePath().normalize();check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")){var p=prepare(Path.of(args[1]),Path.of(args[2]));byte[] b=JSON.writeValueAsBytes(p);
            Files.createDirectory(out);Files.write(out.resolve("plan.json"),b,StandardOpenOption.CREATE_NEW);
            System.out.println("REGRESSION_PREPARED calls=0 reserve="+p.maxReservation()+" sha="+ReferenceOnlyTrial.sha(b));return;}
        check(args[0].equals("run"),"Invalid mode");byte[] b=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(b);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("SYNTHETIC_REGRESSION_CONSENT")),"Exact plan consent required");
        var p=JSON.readValue(b,Plan.class);check(JSON.valueToTree(p).equals(JSON.valueToTree(prepare(Path.of(p.inputsPath()),Path.of(p.labelsPath())))),"Plan drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);Files.createDirectory(out);
        var guard=new TrialGuard();var rows=new ArrayList<Map<String,Object>>();var library=ConstraintBaselineTrial.baseOnly(Files.readString(Path.of(p.runtimePath())));
        save(out,sha,"RUNNING",guard,rows);
        try {for(var run:p.runs()){
            var c=p.cases().stream().filter(x->x.id().equals(run.caseId())).findFirst().orElseThrow();guard.beginCase();
            var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                    .requestInterceptor((req,data,execution)->{byte[] accepted=guard.accept(data,req.getURI().toString());
                        try{save(out,sha,"RUNNING",guard,rows);}catch(Exception e){guard.blocked=true;throw new IllegalStateException("Persistence",e);}
                        req.getHeaders().setContentLength(accepted.length);return execution.execute(req,accepted);}).build();
            var client=new TrialClient(rest,c,run.reference(),false,guard,run,key);client.beginVideo(0L);client.beginAnalyzer("synthetic-regression");
            var result=pipeline(client,c,library);var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());row.put("reference",run.reference());
            row.put("expectedDecision",c.expectedDecision());row.put("status",result.status());row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());rows.add(row);
            boolean ok=acceptResult(guard,result.status().toString(),client.failureCount());save(out,sha,ok?"RUNNING":"STOPPED",guard,rows);client.endVideo();
            System.out.println("REGRESSION_RESULT "+c.id()+" reference="+run.reference()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider, transport or budget failure");
        }save(out,sha,"COMPLETE",guard,rows);}catch(Exception e){save(out,sha,"STOPPED",guard,rows);throw e;}
    }
}
