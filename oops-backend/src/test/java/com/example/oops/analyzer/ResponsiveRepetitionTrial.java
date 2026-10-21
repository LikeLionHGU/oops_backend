package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.TimelineEventType;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Explicitly approved eight-call pilot. Never invoked by JUnit or the application. */
public final class ResponsiveRepetitionTrial {
    static final String CONSENT="NEW_USD025_V88_REPETITION_TEXT_APPROVED_20261021";
    static final String VERSION="v88-responsive-repetition-frozen-1";
    record Plan(String schemaVersion,String model,double cap,int maxAttempts,String sourceSha256,
                String runtimeSha256,Map<String,String> implementation,List<DefaultVerificationPlan.Request> requests,
                double reservationEstimatedUsd) {}
    static String baseline() throws Exception {
        var git=new ProcessBuilder("git","show","3b0ed84:oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewPrompts.java").start();
        byte[] source=git.getInputStream().readAllBytes();
        check(git.waitFor()==0 && source.length>0,"Frozen revision44 source unavailable");
        Path tmp=Files.createTempDirectory("oops-repetition-baseline-");
        Path file=tmp.resolve("CandidateReviewPrompts.java");Files.write(file,source);
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",tmp.toString(),file.toString())==0,"Baseline compilation failed");
        try(var loader=new URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()},null)) {
            var field=loader.loadClass("com.example.oops.analyzer.CandidateReviewPrompts").getDeclaredField("VERIFICATION_PROMPT");
            field.setAccessible(true);
            return ((String)field.get(null)).replace("evidence는 최대 8개로 허용 원문의 segmentId·role을 반환한다.",
                    "evidence는 필요한 허용 원문의 segmentId·role을 반환한다. 고정 개수 상한은 없다. 동일 segmentId·role은 반복하지 않는다.");
        }
    }
    static List<Map<String,Object>> raw(tools.jackson.databind.JsonNode response) {
        check(response.path("success").asBoolean() && response.path("data").size()==27,"Stored88 transcript required");
        var rows=new ArrayList<Map<String,Object>>();int i=0;
        for(var r:response.path("data")) rows.add(Map.of("id","stt-index-"+i++,"startMs",r.path("startMs").asLong(),
                "endMs",r.path("endMs").asLong(),"text",r.path("text").asText()));
        check(rows.get(21).get("text").equals("다이소에서 산 거 같은데?")
                && rows.get(24).get("text").equals("무슨 다이소에서 산 거.."),"Stored88 anchors changed");
        return List.copyOf(rows);
    }
    static String input(List<Map<String,Object>> raw,int anchor,List<Integer> evidence) {
        var ids=raw.stream().map(r -> r.get("id")).toList();
        var quotes=evidence.stream().map(i -> Map.of("segmentId",raw.get(i).get("id"),"quote",raw.get(i).get("text"))).toList();
        return JSON.writeValueAsString(Map.of("promptRevision","fixed-revision-comparison",
                "raw",raw,"candidates",List.of(Map.of("candidateId","candidate-1","anchorId",ids.get(anchor),
                "axis","TARGET_TREATMENT","hypothesisNotEvidence","원문의 행위·대상과 앞뒤 연결을 확인합니다.",
                "proposedEvidence",quotes,"contextExpanded",false,"contextLimited",false,"segmentIds",ids))));
    }
    static List<Map<String,Object>> synthetic(List<String> lines) {
        var raw=new ArrayList<Map<String,Object>>();
        for(int i=0;i<lines.size();i++) raw.add(Map.of("id","stt-index-"+i,"startMs",i*2000L,"endMs",i*2000L+1500,"text",lines.get(i)));
        return raw;
    }
    static Plan plan(byte[] source,byte[] runtime,String oldSystem) throws Exception {
        var raw=raw(JSON.readTree(source));var library=ContextRankReadinessAudit.library(new String(runtime,java.nio.charset.StandardCharsets.UTF_8));
        String reference="\n"+library.prompt(TimelineEventType.SPEECH);
        String current=CandidateReviewEngine.verificationPrompt(0);
        var inputs=List.of(input(raw,21,List.of(21,22,23,24,25,26)),input(raw,24,List.of(21,22,23,24,25,26)),
                input(synthetic(List.of("A: 그 싸구려 물건을 들고 다니는 걸 보니 네 수준을 알겠다.","B: 선물 받은 거야. 그런 말 하지 마.",
                        "A: 다시 말하는데, 그런 물건을 들고 다니는 네 수준이 낮다는 거야.")),2,List.of(0,1,2)),
                input(synthetic(List.of("A: 그 물건 싸구려 같은데?","B: 무슨 싸구려야. 선물 받은 물건이야.",
                        "A: 아, 내가 잘못 말했네. 미안해.")),1,List.of(0,1,2)));
        var names=List.of("V88-first-comparison","V88-responsive-repetition","explicit-repeated-personal-insult","responsive-quotation-without-independent-insult");
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        for(int i=0;i<4;i++) for(String arm:i%2==0?List.of("REVISION_44","REVISION_45"):List.of("REVISION_45","REVISION_44"))
            requests.add(new DefaultVerificationPlan.Request(names.get(i),i<2?"ACTUAL_USER_APPROVED":"SYNTHETIC_USER_APPROVED",
                    i==2?"REVIEW_REQUIRED":"PASS",arm,1,(arm.equals("REVISION_44")?oldSystem:current)+reference,inputs.get(i)));
        double reservation=DefaultVerificationPlan.reservation(requests,"gpt-6-luna");
        var implementation=new TreeMap<>(V7RoutingVideoTrial.implementation());
        implementation.put("responsiveTrial",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/ResponsiveRepetitionTrial.java"))));
        var plan=new Plan(VERSION,"gpt-6-luna",.25,8,ReferenceOnlyTrial.sha(source),ReferenceOnlyTrial.sha(runtime),implementation,List.copyOf(requests),reservation);
        validate(plan);return plan;
    }
    static void validate(Plan p) {
        check(VERSION.equals(p.schemaVersion()) && "gpt-6-luna".equals(p.model()) && p.cap()==.25 && p.maxAttempts()==8
                && p.requests().size()==8 && Double.isFinite(p.reservationEstimatedUsd()) && p.reservationEstimatedUsd()>0
                && p.reservationEstimatedUsd()<=.25,"Fixed eight-call budget required");
        for(int i=0;i<8;i+=2) {
            var a=p.requests().get(i);var b=p.requests().get(i+1);
            check(a.caseId().equals(b.caseId()) && a.input().equals(b.input()) && a.expectedDecision().equals(b.expectedDecision())
                    && !a.system().equals(b.system()) && Set.of(a.arm(),b.arm()).equals(Set.of("REVISION_44","REVISION_45")),"Only system may differ");
            var input=JSON.readTree(a.input());CandidateHypothesisTrial.raw(input);
            check(!input.has("expectedDecision") && !input.has("labelStatus") && input.path("candidates").size()==1,"No labels in model input");
        }
    }
    static void reserve(TextBoundaryTrial.Budget budget,int bytes) throws Exception {
        check(budget.attempts<8,"Eight attempts maximum");budget.reserve(bytes);
    }
    static void save(Path dir,Plan p,String sha,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(dir.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,"results",rows,
                "httpAttempts",budget.attempts,"reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,
                "scope","RECONSTRUCTED_VERIFICATION_ONLY_NOT_ORIGINAL_REQUEST_REPLAY","approvedEstimatedUsd",p.cap())));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"prepare transcript runtime NEW-dir OR run plan sha NEW-dir");
        Path output=Path.of(args[3]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private directory required");
        if(args[0].equals("prepare")) {
            var p=plan(OfflineDecisionContractAudit.bounded(Path.of(args[1])),OfflineDecisionContractAudit.bounded(Path.of(args[2])),baseline());
            byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("PREPARED providerCalls=0 attempts=8 reservation="+p.reservationEstimatedUsd()+" sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        check(args[0].equals("run") && CONSENT.equals(System.getenv("RESPONSIVE_TRIAL_CONSENT")),"Fresh explicit consent required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan hash mismatch");
        var p=JSON.readValue(bytes,Plan.class);validate(p);
        var implementation=new TreeMap<>(V7RoutingVideoTrial.implementation());
        implementation.put("responsiveTrial",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/ResponsiveRepetitionTrial.java"))));
        check(p.implementation().equals(implementation)
                && Double.compare(p.reservationEstimatedUsd(),DefaultVerificationPlan.reservation(p.requests(),p.model()))==0,"Implementation or reservation drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),output,args[2],CONSENT,8);
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
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("responsive-repetition-pilot");
                guard.begin(JSON.writeValueAsBytes(client.replayRequestBody(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class)));
                var response=client.completeAsJson(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage=client.videoUsage();cost+=usage.costUsd();
                var row=new LinkedHashMap<String,Object>();row.put("caseId",r.caseId());row.put("arm",r.arm());row.put("expectedDecision",r.expectedDecision());
                row.put("assessment",DefaultVerificationPlan.assess(r,response));row.put("calls",List.copyOf(client.calls));row.put("usage",usage);rows.add(row);
                save(output,p,args[2],rows,budget,cost,"RUNNING");
                boolean ok=client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;client.endVideo();
                System.out.println("PILOT "+rows.size()+"/8 "+r.caseId()+" "+r.arm());check(ok,"Provider failure: stop without retry");
            }
            check(budget.attempts==8,"HTTP accounting mismatch");save(output,p,args[2],rows,budget,cost,"COMPLETE");
            System.out.println("COMPLETE usageEstimate="+cost+" reserved="+budget.reserved);
        } catch(Exception ex) {save(output,p,args[2],rows,budget,cost,"STOPPED");throw ex;}
    }
}
