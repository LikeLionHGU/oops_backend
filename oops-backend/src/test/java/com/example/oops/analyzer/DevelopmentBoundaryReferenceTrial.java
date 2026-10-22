package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.TimelineEventType;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Explicitly approved six-call pilot. Never invoked by JUnit or the application. */
public final class DevelopmentBoundaryReferenceTrial {
    static final String CONSENT="NEW_USD025_V99_REFERENCE_TEXT_APPROVED_20261022";
    static final String VERSION="v99-development-reference-frozen-1";
    record Plan(String schemaVersion,String model,double cap,int maxAttempts,String sourceSha256,
                String runtimeSha256,Map<String,String> implementation,List<DefaultVerificationPlan.Request> requests,
                double reservationEstimatedUsd) {}
    static Plan plan(byte[] source,byte[] before,byte[] after) throws Exception {
        var frozen=JSON.readTree(source);
        check(frozen.path("videoId").asInt()==99 && "stt-index-50".equals(frozen.path("anchorId").asText())
                && !frozen.path("historicalRequestReconstructed").asBoolean(),"Stored99 excerpt required");
        var raw=CandidateHypothesisTrial.raw(frozen).segments();
        check(raw.size()==22,"Stored22-line window required");
        var anchor=raw.stream().filter(s -> s.id().equals("stt-index-50")).findFirst().orElseThrow();
        var ids=raw.stream().map(ReviewInput.Segment::id).toList();
        var input=JSON.writeValueAsString(Map.of("promptRevision",CandidateReviewEngine.REVISION,
                "raw",frozen.get("raw"),"candidates",List.of(Map.of(
                "candidateId","candidate-1","anchorId",anchor.id(),"axis","TARGET_TREATMENT",
                "hypothesisNotEvidence","재구성 입력 연결 확인용 후보이며 당시 탐색 가설이 아니다.",
                "proposedEvidence",List.of(Map.of("segmentId",anchor.id(),"quote",anchor.text())),
                "contextExpanded",false,"contextLimited",true,"segmentIds",ids))));
        var archives=List.of(before,after);
        var refs=new ArrayList<String>();var selections=new ArrayList<List<String>>();
        for(var bytes:archives) {
            var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(
                    new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),true,"classpath:frozen.json",6000,3,3000,"",true);
            library.load();
            check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),"Ready archive required");
            var selection=library.select(TimelineEventType.SPEECH,raw,"fixed-99-reference",List.of(anchor));
            refs.add(selection.prompt()); selections.add(selection.trace().exampleIds());
        }
        check(!selections.get(0).contains("mimiminu-dev#audience-devaluation")
                && selections.get(1).contains("mimiminu-dev#audience-devaluation"),"Before/after retrieval required");
        String common=CandidateReviewEngine.verificationPrompt(0);
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        for(int repeat=1;repeat<=3;repeat++) for(String arm:repeat%2==1?List.of("BEFORE","AFTER"):List.of("AFTER","BEFORE"))
            requests.add(new DefaultVerificationPlan.Request("V99-reconstructed","USER_APPROVED_DEVELOPMENT_BOUNDARY",
                    "REVIEW_REQUIRED",arm,repeat,common+refs.get(arm.equals("BEFORE")?0:1),input));
        var implementation=implementation();
        implementation.put("beforeRuntime",ReferenceOnlyTrial.sha(before));
        implementation.put("afterRuntime",ReferenceOnlyTrial.sha(after));
        implementation.put("beforeSelected",String.join(",",selections.get(0)));
        implementation.put("afterSelected",String.join(",",selections.get(1)));
        var p=new Plan(VERSION,"gpt-6-luna",.25,6,ReferenceOnlyTrial.sha(source),
                ReferenceOnlyTrial.sha((ReferenceOnlyTrial.sha(before)+ReferenceOnlyTrial.sha(after)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                implementation,List.copyOf(requests),DefaultVerificationPlan.reservation(requests,"gpt-6-luna"));
        validate(p); return p;
    }
    static TreeMap<String,String> implementation() throws Exception {
        var result=new TreeMap<>(V7RoutingVideoTrial.implementation());
        result.put("boundaryTrial",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/DevelopmentBoundaryReferenceTrial.java"))));
        return result;
    }
    static void validate(Plan p) {
        check(VERSION.equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model()) && p.cap()==.25 && p.maxAttempts()==6
                && p.requests().size()==6 && Double.isFinite(p.reservationEstimatedUsd()) && p.reservationEstimatedUsd()>0
                && p.reservationEstimatedUsd()<=.25,"Fixed six-call budget required");
        String commonInput=p.requests().get(0).input();
        var systems=new HashMap<String,String>();
        for(int i=0;i<6;i++) {
            var r=p.requests().get(i);
            check(Set.of("BEFORE","AFTER").contains(r.arm()) && r.repeat()==i/2+1
                    && r.expectedDecision().equals("REVIEW_REQUIRED") && r.input().equals(commonInput),"Only references may differ");
            String previous=systems.putIfAbsent(r.arm(),r.system());
            check(previous==null || previous.equals(r.system()),"System drift within arm");
            check(r.system().startsWith(CandidateReviewEngine.verificationPrompt(0)),"Common judgment prompt required");
            var input=JSON.readTree(r.input()); CandidateHypothesisTrial.raw(input);
            check(!input.has("expectedDecision") && !input.has("labelStatus")
                    && input.path("candidates").size()==1,"No labels in model input");
        }
        for(int i=0;i<6;i+=2) check(!p.requests().get(i).arm().equals(p.requests().get(i+1).arm()),"Paired order required");
        check(systems.size()==2 && !systems.get("BEFORE").equals(systems.get("AFTER")),"Reference difference required");
    }
    static void reserve(TextBoundaryTrial.Budget budget,int bytes) throws Exception {
        check(budget.attempts<6,"Six attempts maximum");budget.reserve(bytes);
    }
    static void save(Path dir,Plan p,String sha,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(dir.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,"results",rows,
                "httpAttempts",budget.attempts,"reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,
                "scope","RECONSTRUCTED_VERIFICATION_ONLY_NOT_ORIGINAL_REQUEST_REPLAY","approvedEstimatedUsd",p.cap())));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==5 && args[0].equals("prepare") || args.length==4 && args[0].equals("run"),"prepare window before after NEW-dir OR run plan sha NEW-dir");
        Path output=Path.of(args[args.length-1]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required");
        if(args[0].equals("prepare")) {
            var p=plan(OfflineDecisionContractAudit.bounded(Path.of(args[1])),OfflineDecisionContractAudit.bounded(Path.of(args[2])),OfflineDecisionContractAudit.bounded(Path.of(args[3])));
            byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("PREPARED providerCalls=0 attempts=6 reservation="+p.reservationEstimatedUsd()+" sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        check(args[0].equals("run") && CONSENT.equals(System.getenv("BOUNDARY_REFERENCE_TRIAL_CONSENT")),"Fresh explicit consent required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan hash mismatch");
        var p=JSON.readValue(bytes,Plan.class);validate(p);
        var implementation=implementation();
        for(String k:List.of("beforeRuntime","afterRuntime","beforeSelected","afterSelected")) implementation.put(k,p.implementation().get(k));
        check(p.implementation().equals(implementation)
                && Double.compare(p.reservationEstimatedUsd(),DefaultVerificationPlan.reservation(p.requests(),p.model()))==0,"Implementation or reservation drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),output,args[2],CONSENT,6);
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var budget=new TextBoundaryTrial.Budget(.25,.10,.50,output);var guard=new DefaultVerificationTrial.RequestGuard();
        var props=new OpenAiProperties(key,null,null,"https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(60),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((req,body,execution)-> {
                    byte[] approved=guard.accept(body,req.getURI().toString());
                    try {reserve(budget,approved.length);} catch(Exception ex) {throw new IllegalArgumentException("Budget guard failed",ex);}
                    req.getHeaders().setContentLength(approved.length);return execution.execute(req,approved);
                }).build();
        var client=new MechanismRoutingTrial.Client(rest,props);var rows=new ArrayList<Map<String,Object>>();double cost=0;
        save(output,p,args[2],rows,budget,cost,"RUNNING");
        try {
            for(var r:p.requests()) {
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("development-reference-pilot");
                guard.begin(JSON.writeValueAsBytes(client.replayRequestBody(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class)));
                var response=client.completeAsJson(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage=client.videoUsage();cost+=usage.costUsd();
                var row=new LinkedHashMap<String,Object>();row.put("caseId",r.caseId());row.put("arm",r.arm());row.put("repeat",r.repeat());row.put("expectedDecision",r.expectedDecision());
                row.put("assessment",DefaultVerificationPlan.assess(r,response));row.put("calls",List.copyOf(client.calls));row.put("usage",usage);rows.add(row);
                save(output,p,args[2],rows,budget,cost,"RUNNING");
                boolean ok=client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;client.endVideo();
                System.out.println("PILOT "+rows.size()+"/6 "+r.caseId()+" "+r.arm());check(ok,"Provider failure: stop without retry");
            }
            check(budget.attempts==6,"HTTP accounting mismatch");save(output,p,args[2],rows,budget,cost,"COMPLETE");
            System.out.println("COMPLETE usageEstimate="+cost+" reserved="+budget.reserved);
        } catch(Exception ex) {save(output,p,args[2],rows,budget,cost,"STOPPED");throw ex;}
    }
}
