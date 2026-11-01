package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ConfrontationStageTrial.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Opt-in fixed development baseline; base criteria only, no examples or media. */
public final class FixedBoundaryBaselineTrial {
    static final String CONSENT="FIXED12_USD050_TEXT_BASE_GUIDELINES_ONLY";
    static final int LIMIT=24, PER_CASE=2;
    static final List<String> IDS=List.of("R03","R04","R05","R06","R07","R08","R01-166","R01-178","A01","A02","A03","A04");
    static final String INPUT_SHA="f4ea29f87b55b0342f714f274e39abba5bb52fd048d1ad058b58e5c62c7ca57b";
    static void enforceLimits(Guard guard) {
        if(guard.attempts>=LIMIT||guard.caseAttempts>=PER_CASE){guard.blocked=true;throw new IllegalStateException("Trial HTTP limit");}
    }
    record Plan(String inputsPath,String inputsSha,String labelsPath,String labelsSha,String runtimePath,String runtimeSha,
                Map<String,String> implementation,List<Condition> cases,List<Frozen> requests,double maxReservation) {}
    static Plan prepare(Path input,Path label)throws Exception {
        byte[] inputRaw=OfflineDecisionContractAudit.bounded(input), labelRaw=OfflineDecisionContractAudit.bounded(label);
        check(ReferenceOnlyTrial.sha(inputRaw).equals(INPUT_SHA),"Frozen evaluation input changed");
        var rows=JSON.readTree(inputRaw);var labels=JSON.readTree(labelRaw);
        check(rows.size()==12&&labels.size()==12,"Fixed 12 conditions required");
        var cases=new ArrayList<Condition>();
        for(int i=0;i<12;i++) {
            var row=rows.get(i);var lab=labels.get(i);String id=row.path("caseId").asText();
            String expected=lab.path("expectedDecision").asText();
            check(id.equals(IDS.get(i))&&id.equals(lab.path("caseId").asText())
                    &&lab.path("labelScope").asText().startsWith("USER_APPROVED_")
                    &&Set.of("PASS","REVIEW_REQUIRED").contains(expected),"Approved evaluation required");
            var segments=new ArrayList<ReviewInput.Segment>();
            for(var seg:row.path("raw"))segments.add(new ReviewInput.Segment(seg.path("id").asText(),TimelineEventType.SPEECH,
                    seg.path("startMs").asLong(),seg.path("endMs").asLong(),seg.path("text").asText(),null));
            check(!segments.isEmpty()&&segments.size()<=64,"Bounded segments required");
            String anchor=row.path("anchorId").asText();check(segments.stream().anyMatch(s->s.id().equals(anchor)),"Anchor missing");
            cases.add(new Condition(id,"DEVELOPMENT_ONLY_NOT_INDEPENDENT_GOLD",expected,List.copyOf(segments),
                    new Seed(anchor,"EXPRESSION_CONTENT","diagnostic only",List.of(anchor))));
        }
        Path runtimePath=Path.of("../datasets/controversy/guidelines/runtime.json").toRealPath();String runtime=Files.readString(runtimePath);
        var library=ConstraintBaselineTrial.baseOnly(runtime);var requests=new ArrayList<Frozen>();
        for(var c:cases){var client=new Client(RestClient.create(),c,"AUTO",new Guard(),true,List.of(),"offline-no-key");
            pipeline(client,c,library);check(client.captured.size()==1,"One discovery page per condition required");
            var f=client.captured.get(0);check(!f.system().contains("referenceCases:"),"Case references forbidden");
            var marker="contextReference=";int start=f.system().indexOf(marker);check(start>=0,"Base criteria missing");
            var context=JSON.readTree(f.system().substring(start+marker.length()));
            check(context.path("referenceContexts").isArray()&&context.path("referenceContexts").isEmpty(),"Examples forbidden");
            requests.add(f);
        }
        var impl=new TreeMap<>(implementation());for(String f:List.of("ConstraintBaselineTrial.java","FixedBoundaryBaselineTrial.java")) {
            var path=Path.of("src/test/java/com/example/oops/analyzer/"+f);impl.put(path.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(path)));}
        double worst=LIMIT*reservation(MAX_BYTES);check(worst<=CAP,"Reservation exceeds cap");
        return new Plan(input.toRealPath().toString(),ReferenceOnlyTrial.sha(inputRaw),label.toRealPath().toString(),
                ReferenceOnlyTrial.sha(labelRaw),runtimePath.toString(),ReferenceOnlyTrial.sha(runtime.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                impl,List.copyOf(cases),List.copyOf(requests),worst);
    }
    static void save(Path out,String sha,String state,Guard guard,List<Map<String,Object>> rows)throws Exception {
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,
                "attempts",guard.attempts,"reservedEstimatedUsd",guard.reserved,"capUsd",CAP,"actualBillingConfirmed",false,
                "scope","FIXED_DEVELOPMENT_BASELINE_NOT_INDEPENDENT_NOT_CASE_EFFECT","results",rows)));
    }
    public static void main(String[] args)throws Exception {
        check((args.length==4&&args[0].equals("prepare"))||(args.length==4&&args[0].equals("run")),"Invalid mode");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath(),out=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(out)&&out.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")){var plan=prepare(Path.of(args[1]),Path.of(args[2]));byte[] bytes=JSON.writeValueAsBytes(plan);
            Files.createDirectory(out);Files.write(out.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("FIXED12_PREPARED providerCalls=0 maxHttp=24 maxReservation="+plan.maxReservation()+" sha="+ReferenceOnlyTrial.sha(bytes));return;}
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("FIXED12_CONSENT")),"Fresh exact-plan consent required");
        var plan=JSON.readValue(bytes,Plan.class);var fresh=prepare(Path.of(plan.inputsPath()),Path.of(plan.labelsPath()));
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)),"Source/runtime/code drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,sha,CONSENT,LIMIT);
        Files.createDirectory(out);var guard=new Guard();var results=new ArrayList<Map<String,Object>>();
        var library=ConstraintBaselineTrial.baseOnly(Files.readString(Path.of(plan.runtimePath())));save(out,sha,"RUNNING",guard,results);
        try {for(var c:plan.cases()) {
            guard.beginCase();var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
            factory.setReadTimeout(Duration.ofSeconds(60));
            var rest=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory)
                    .defaultHeader("Authorization","Bearer "+key).requestInterceptor((request,body,execution)->{
                        enforceLimits(guard);
                        byte[] accepted=guard.accept(body,request.getURI().toString());
                        try{save(out,sha,"RUNNING",guard,results);}catch(Exception ex){throw new IllegalStateException("Reservation persistence failed",ex);}
                        request.getHeaders().setContentLength(accepted.length);return execution.execute(request,accepted);
                    }).build();
            var client=new Client(rest,c,"AUTO",guard,false,plan.requests(),key);client.beginVideo(0L);client.beginAnalyzer("fixed12-baseline");
            var result=pipeline(client,c,library);var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());
            row.put("expectedDecision",c.expectedDecision());row.put("status",result.status());row.put("diagnostics",result.diagnostics());
            row.put("evaluations",result.evaluations());row.put("usage",client.videoUsage());
            row.put("findings",result.findings().stream().map(f->Map.of("text",f.primaryText(),"reason",f.getReason())).toList());
            results.add(row);boolean ok=!guard.blocked&&client.failureCount()==0;save(out,sha,ok?"RUNNING":"STOPPED",guard,results);
            client.endVideo();System.out.println("FIXED12_RESULT "+c.id()+" status="+result.status()+" findings="+result.findings().size());
            check(ok,"Provider/transport/cap failure");
        }save(out,sha,"COMPLETE",guard,results);}catch(Exception ex){save(out,sha,"STOPPED",guard,results);throw ex;}
    }
}
