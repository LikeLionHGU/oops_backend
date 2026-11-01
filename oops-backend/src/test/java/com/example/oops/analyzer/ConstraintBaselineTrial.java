package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.TimelineEventType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Opt-in synthetic-only baseline. No case examples, video, audio or source reports. */
public final class ConstraintBaselineTrial {
    static final String CONSENT = "CONSTRAINT_BASELINE_USD050_SYNTHETIC_AND_BASE_GUIDELINES";
    static final int LIMIT = 8;
    record Plan(String sourcePath,String sourceSha,String runtimePath,String runtimeSha,Map<String,String> implementation,
                List<Condition> cases,List<Frozen> requests,double maxReservation) {}

    static ReviewGuidelineLibrary baseOnly(String runtime) {
        var library = new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime),true,
                "classpath:frozen.json",6000,0,3000,"",true,true);
        library.load();
        check(library.trace(TimelineEventType.SPEECH).state().equals("READY_WORKING_REFERENCE"),"Archive unavailable");
        return library;
    }
    static Plan prepare(Path source) throws Exception {
        byte[] raw=OfflineDecisionContractAudit.bounded(source);var packet=JSON.readTree(raw);
        check(packet.path("status").asText().equals("USER_APPROVED_DEVELOPMENT_ONLY"),"Approval required");
        check(packet.path("origin").asText().equals("SYNTHETIC_DEVELOPMENT_CONTROL_NOT_ORIGINAL_VIDEO"),"Synthetic controls required");
        var rows=packet.path("controls");check(rows.size()==2,"Two controls required");
        var cases=new ArrayList<Condition>();
        var expected=List.of("PASS","REVIEW_REQUIRED");
        var ids=List.of("synthetic-constraint-consult","synthetic-constraint-blame");
        for(int i=0;i<2;i++) {
            var row=rows.get(i);check(row.path("id").asText().equals(ids.get(i))
                    &&row.path("humanApproved").asBoolean()&&row.path("proposedLabel").asText().equals(expected.get(i))
                    &&row.path("dialogue").size()==2,"Approved controls must match");
            var segments=new ArrayList<ReviewInput.Segment>();
            for(int j=0;j<2;j++)segments.add(new ReviewInput.Segment("stt-index-"+j,TimelineEventType.SPEECH,
                    j*2000L,j*2000L+1500,row.path("dialogue").get(j).asText(),null));
            cases.add(new Condition(ids.get(i),"SYNTHETIC_DEVELOPMENT_ONLY",expected.get(i),List.copyOf(segments),
                    new Seed("stt-index-1","EXPRESSION_CONTENT","진단용 가설",List.of("stt-index-1"))));
        }
        var runtimePath=Path.of("../datasets/controversy/guidelines/runtime.json").toRealPath();
        String runtime=Files.readString(runtimePath);var library=baseOnly(runtime);var requests=new ArrayList<Frozen>();
        for(var c:cases){var client=new Client(RestClient.create(),c,"AUTO",new Guard(),true,List.of(),"offline-no-key");
            pipeline(client,c,library);check(client.captured.size()==1,"One initial discovery required");
            var f=client.captured.get(0);check(!f.system().contains("referenceCases:"),"No case examples allowed");
            check(!ResearchCaseAliases.containsIncidentReference(f.system()),"No incident reference allowed");requests.add(f);}
        var impl=new TreeMap<>(implementation());impl.put("src/test/java/com/example/oops/analyzer/ConstraintBaselineTrial.java",
                ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/ConstraintBaselineTrial.java"))));
        double max=LIMIT*reservation(MAX_BYTES);check(max<=CAP,"Reservation exceeds cap");
        return new Plan(source.toRealPath().toString(),ReferenceOnlyTrial.sha(raw),runtimePath.toString(),
                ReferenceOnlyTrial.sha(runtime.getBytes(java.nio.charset.StandardCharsets.UTF_8)),impl,List.copyOf(cases),List.copyOf(requests),max);
    }
    static void save(Path out,String sha,String state,Guard guard,List<Map<String,Object>> rows)throws Exception {
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,
                "attempts",guard.attempts,"reservedEstimatedUsd",guard.reserved,"capUsd",CAP,"actualBillingConfirmed",false,
                "scope","SYNTHETIC_BASE_ONLY_NOT_CASE_EFFECT_NOT_VIDEO_ACCURACY","results",rows)));
    }
    public static void main(String[] args)throws Exception {
        check((args.length==3&&args[0].equals("prepare"))||(args.length==4&&args[0].equals("run")),"Invalid mode");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath(), out=Path.of(args[args.length-1]).toAbsolutePath().normalize();
        check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")) {var plan=prepare(Path.of(args[1]));byte[] bytes=JSON.writeValueAsBytes(plan);
            Files.createDirectory(out);Files.write(out.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("CONSTRAINT_PREPARED providerCalls=0 maxHttp=8 maxReservation="+plan.maxReservation()+" sha="+ReferenceOnlyTrial.sha(bytes));return;}
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("CONSTRAINT_BASELINE_CONSENT")),"Fresh exact-plan consent required");
        var plan=JSON.readValue(bytes,Plan.class);var fresh=prepare(Path.of(plan.sourcePath()));
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)),"Source/runtime/code drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);
        Files.createDirectory(out);var guard=new Guard();var results=new ArrayList<Map<String,Object>>();
        var library=baseOnly(Files.readString(Path.of(plan.runtimePath())));save(out,sha,"RUNNING",guard,results);
        try {for(var c:plan.cases()) {
            guard.beginCase();var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
            factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                    .defaultHeader("Authorization","Bearer "+key).requestInterceptor((request,body,execution)->{
                        check(guard.attempts<LIMIT,"Trial HTTP limit");byte[] accepted=guard.accept(body,request.getURI().toString());
                        try {save(out,sha,"RUNNING",guard,results);}catch(Exception ex){throw new IllegalStateException("Reservation persistence failed",ex);}
                        request.getHeaders().setContentLength(accepted.length);return execution.execute(request,accepted);
                    }).build();
            var client=new Client(rest,c,"AUTO",guard,false,plan.requests(),key);client.beginVideo(0L);client.beginAnalyzer("constraint-baseline");
            var result=pipeline(client,c,library);var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());
            row.put("expectedDecision",c.expectedDecision());row.put("status",result.status());row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());
            results.add(row);boolean ok=!guard.blocked&&client.failureCount()==0;save(out,sha,ok?"RUNNING":"STOPPED",guard,results);
            client.endVideo();System.out.println("CONSTRAINT_RESULT "+c.id()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider/transport/cap failure");
        }save(out,sha,"COMPLETE",guard,results);}catch(Exception ex){save(out,sha,"STOPPED",guard,results);throw ex;}
    }
}
