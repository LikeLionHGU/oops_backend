package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fixed verification inputs: no new discovery, repair, audio, application or automatic promotion. */
public final class UtteranceDirectionTrial {
    static final String CONSENT="NEW_USD050_UTTERANCE_DIRECTION_TEXT_EGRESS_APPROVED";
    static final String BASIS_REVISION="final-basis-verification-comparison-20261016-1";
    static final String BASIS_CONSENT="NEW_USD050_FINAL_BASIS_TEXT_EGRESS_APPROVED";
    static final int MAX_HTTP_ATTEMPTS=56;
    record Task(String id,String labelStatus,String expectedDecision,String relationDraft,String unsupportedInference,
                String baselineSystem,String comparisonSystem,String input) {}
    record Plan(String schemaVersion,String model,int repeats,double estimatedCapUsd,String sourceSha256,
                String runtimeSha256,String normalFixtureSha256,String relationFixtureSha256,List<Task> tasks) {}
    static boolean basis(Plan plan) { return BASIS_REVISION.equals(plan.schemaVersion()); }
    static Plan basisPlan(Plan original) throws Exception {
        validate(original);
        var tasks=original.tasks().stream().map(t -> new Task(t.id(),t.labelStatus(),t.expectedDecision(),
                t.relationDraft(),t.unsupportedInference(),t.baselineSystem(),FinalReviewBasisContract.prompt(t.baselineSystem()),t.input())).toList();
        var plan=new Plan(BASIS_REVISION,original.model(),original.repeats(),original.estimatedCapUsd(),original.sourceSha256(),
                original.runtimeSha256(),original.normalFixtureSha256(),original.relationFixtureSha256(),tasks);
        validate(plan);return plan;
    }
    static String requestInput(Plan plan,Task task,boolean comparison) {
        if(!basis(plan) || !comparison) return task.input();
        var input=(tools.jackson.databind.node.ObjectNode)JSON.readTree(task.input());
        input.put("verificationContract",FinalReviewBasisContract.VERSION);
        return input.toString();
    }
    static byte[] fixture(String path) throws Exception {
        try(var in=UtteranceDirectionTrial.class.getResourceAsStream(path)) { return Objects.requireNonNull(in).readAllBytes(); }
    }
    static Task pair(String id,String status,String expected,String relation,String unsupported,String system,String input) {
        return new Task(id,status,expected,relation,unsupported,system,UtteranceDirectionPolicy.comparison(system),input);
    }
    static Task capture(String id,String status,String expected,String relation,String unsupported,List<String> speech,
                        int anchor,ReviewGuidelineLibrary library) {
        var f=new CandidateReviewEngineTest();
        var video=Video.builder().filename("synthetic-relative-time").build();
        var transcript=new ArrayList<TranscriptSegment>();
        for(int i=0;i<speech.size();i++) transcript.add(new TranscriptSegment(video,i*2000L,i*2000L+1500,speech.get(i)));
        String anchorId="stt-index-"+anchor;
        var evidence=java.util.stream.IntStream.range(0,speech.size()).mapToObj(i -> new Quote("stt-index-"+i,null)).toList();
        var proposal=new Proposal(anchorId,"TARGET_TREATMENT","원문의 행위·대상과 앞뒤 연결을 확인합니다.",evidence);
        when(f.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(call -> {
            var input=JSON.readTree((String)call.getArgument(1));var ids=new ArrayList<String>();
            input.path("primaryIds").forEach(n -> ids.add(n.asText()));
            return Optional.of(new Discovery(ids,List.of(proposal),List.of(),false));
        });
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenReturn(Optional.empty());
        run(f.client,new AnalysisContext(video,ContentGenre.GENERAL,transcript,List.of()),24,null,null,library,null,0,false,null);
        var systems=ArgumentCaptor.forClass(String.class);var inputs=ArgumentCaptor.forClass(String.class);
        verify(f.client,times(1)).completeAsJson(systems.capture(),inputs.capture(),eq(VerificationResult.class));
        return pair(id,status,expected,relation,unsupported,systems.getValue(),inputs.getValue());
    }
    static Plan prepare(byte[] source,byte[] runtime) throws Exception {
        var saved=JSON.readTree(source);
        check("discovery-id-scope-results-1".equals(saved.path("schemaVersion").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && saved.path("results").size()==32,"Complete scoped study required");
        var selected=new ArrayList<JsonNode>();
        saved.path("results").forEach(r -> {
            if("v4-intimate-question-groq".equals(r.path("caseId").asText())
                    && "SCOPED".equals(r.path("arm").asText()) && r.path("repeat").asInt()==1) selected.add(r);
        });
        check(selected.size()==1,"Exactly one fixed source row required");
        var tasks=new ArrayList<Task>();
        for(String anchor:List.of("groq-v4-166","groq-v4-178")) {
            JsonNode found=null;
            for(var call:selected.get(0).path("calls")) if("VerificationResult".equals(call.path("kind").asText())
                    && !call.path("input").has("repair") && call.at("/input/candidates").size()==1
                    && anchor.equals(call.at("/input/candidates/0/anchorId").asText())) { check(found==null,"Duplicate initial source request");found=call; }
            check(found!=null,"Initial fixed source request missing");
            tasks.add(pair(anchor,"STORED_EXCERPT_NOT_INDEPENDENT_GOLD",null,null,null,
                    found.path("system").asText(),found.path("input").toString()));
        }
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(new String(runtime,java.nio.charset.StandardCharsets.UTF_8)),
                true,"classpath:frozen.json",6000,3,3000,"",true);library.load();
        byte[] normal=fixture("/structured-search-boundaries.json"),relation=fixture("/utterance-direction-boundaries.json");
        var normalSuite=JSON.readValue(normal,StructuredSearchBoundaryTrial.Suite.class);
        for(var b:normalSuite.cases()) tasks.add(capture(b.id(),normalSuite.labelStatus(),b.expectedDecision(),null,null,b.speech(),0,library));
        var relationSuite=JSON.readValue(relation,UtteranceDirectionBoundaryTest.Suite.class);
        for(var b:relationSuite.cases()) tasks.add(capture("relation-"+b.id(),relationSuite.labelStatus(),null,b.relationDraft(),
                b.unsupportedInference(),b.speech(),Set.of("self-offer","other-request","term-definition").contains(b.id())?1:0,library));
        var plan=new Plan(UtteranceDirectionPolicy.REVISION,"gpt-6-luna",2,.50,ReferenceOnlyTrial.sha(source),
                ReferenceOnlyTrial.sha(runtime),ReferenceOnlyTrial.sha(normal),ReferenceOnlyTrial.sha(relation),List.copyOf(tasks));
        validate(plan);return plan;
    }
    static void validate(Plan plan) throws Exception {
        check((UtteranceDirectionPolicy.REVISION.equals(plan.schemaVersion()) || basis(plan)) && "gpt-6-luna".equals(plan.model())
                && plan.repeats()==2 && plan.estimatedCapUsd()==.50 && plan.tasks()!=null && plan.tasks().size()==14
                && plan.tasks().stream().map(Task::id).distinct().count()==14,"Known 56-call plan required");
        check(ReferenceOnlyTrial.sha(fixture("/structured-search-boundaries.json")).equals(plan.normalFixtureSha256())
                && ReferenceOnlyTrial.sha(fixture("/utterance-direction-boundaries.json")).equals(plan.relationFixtureSha256()),"Boundary fixtures changed");
        for(var task:plan.tasks()) {
            check((basis(plan)?FinalReviewBasisContract.prompt(task.baselineSystem()):UtteranceDirectionPolicy.comparison(task.baselineSystem()))
                    .equals(task.comparisonSystem()),"Comparison changes only direction guidance or selected final basis contract");
            var input=JSON.readTree(task.input());
            check(input.path("raw").isArray() && !input.path("raw").isEmpty() && input.path("raw").size()<=48
                    && input.path("candidates").size()==1 && !input.has("repair") && !input.has("expectedDecision")
                    && !input.has("relationDraft") && !input.has("unsupportedInference") && !input.has("verificationContract"),"Fixed unlabeled verification input required");
        }
        check(reservationEstimate(plan)<=plan.estimatedCapUsd(),"Frozen request reservation exceeds fresh cap");
    }
    static double reservationEstimate(Plan plan) {
        var props=new OpenAiProperties("offline-test-key",null,null,"https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(2),null);
        var client=new com.example.oops.client.OpenAiClient(RestClient.create(),props);
        double total=0;
        for(var task:plan.tasks()) for(boolean comparison:List.of(false,true)) {
            var body=JSON.writeValueAsBytes(client.replayRequestBody(comparison?task.comparisonSystem():task.baselineSystem(),
                    requestInput(plan,task,comparison),VerificationResult.class));
            var guarded=V7RoutingVideoTrial.guardBody(body,"https://api.openai.com/v1/chat/completions");
            total+=plan.repeats()*((guarded.length+1024)*.10/1_000_000+8000*.50/1_000_000);
        }
        return total;
    }
    static void reserve(StructuredSearchBoundaryTrial.Budget budget,int bytes) throws Exception {
        if(budget.attempts>=MAX_HTTP_ATTEMPTS) { budget.exhausted=true;throw new IllegalArgumentException("56 HTTP attempt cap reached"); }
        budget.reserve(bytes);
    }
    static Map<String,Object> assess(Task task,VerificationResult response) {
        return assess(task,response,null);
    }
    static Map<String,Object> assess(Task task,VerificationResult response,Boolean finalBasisEnabled) {
        var input=JSON.readTree(task.input());var c=input.path("candidates").get(0);
        var raw=CandidateHypothesisTrial.raw(input);var quotes=new ArrayList<Quote>();
        c.path("proposedEvidence").forEach(q -> quotes.add(new Quote(q.path("segmentId").asText(),q.path("quote").asText())));
        var proposal=new Proposal(c.path("anchorId").asText(),c.path("axis").asText(),c.path("hypothesisNotEvidence").asText(),quotes);
        Set<String> allowed=new HashSet<>();c.path("segmentIds").forEach(n -> allowed.add(n.asText()));
        var candidate=new Candidate(c.path("candidateId").asText(),proposal,
                raw.segments().stream().filter(s -> allowed.contains(s.id())).toList(),c.path("contextExpanded").asBoolean(),c.path("contextLimited").asBoolean());
        var v=new Validation(null,"TRIAL_RESPONSE_SHAPE");
        if(response!=null && response.verifications()!=null && response.verifications().size()==1
                && response.verifications().get(0)!=null && candidate.candidateId().equals(response.verifications().get(0).candidateId()))
            v=finalBasisEnabled==null?CandidateReviewEngine.validate(candidate,response.verifications().get(0).assessment(),raw,0)
                    :FinalReviewBasisContract.validate(candidate,response.verifications().get(0),raw,0,finalBasisEnabled);
        var row=new LinkedHashMap<String,Object>();row.put("accepted",v.observation()!=null);
        row.put("failureCode",v.failureCode());row.put("observation",v.observation());row.put("evidenceMeaningReview","NOT_AUTOMATICALLY_ASSESSED");
        return row;
    }
    static void save(Path output,Plan plan,String sha,List<Map<String,Object>> rows,StructuredSearchBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion",plan.schemaVersion(),
                "planSha256",sha,"state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,"maxHttpAttempts",MAX_HTTP_ATTEMPTS)));
    }
    public static void main(String[] args) throws Exception {
        check(args.length>=1 && (Set.of("prepare","prepare-basis").contains(args[0]) && args.length==4
                        || Set.of("run","run-basis").contains(args[0]) && args.length==5),
                "prepare saved-results runtime NEW-dir OR run plan approved-sha fresh-consent NEW-dir");
        Path output=Path.of(args[args.length-1]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].startsWith("prepare")) {
            var plan=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[1])),OfflineDecisionContractAudit.bounded(Path.of(args[2])));
            if(args[0].equals("prepare-basis")) plan=basisPlan(plan);
            Files.createDirectory(output);byte[] bytes=JSON.writeValueAsBytes(plan);
            Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("DIRECTION_PREPARED tasks=14 calls=56 providerCalls=0 reservation="+reservationEstimate(plan)+" planSha256="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan required");
        var plan=JSON.readValue(bytes,Plan.class);validate(plan);
        check(basis(plan)==args[0].equals("run-basis") && (basis(plan)?BASIS_CONSENT:CONSENT).equals(args[3]),
                "Selected contract and fresh cost/text approval required");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var budget=new StructuredSearchBoundaryTrial.Budget(output,.50);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { reserve(budget,guarded.length); } catch(java.io.IOException ex) { throw ex; }
                    catch(Exception ex) { throw new IllegalArgumentException("Reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(builder.build(),props);
        var rows=new ArrayList<Map<String,Object>>();double cost=0;save(output,plan,args[2],rows,budget,cost,"RUNNING");
        try {
            for(int repeat=1;repeat<=2;repeat++) for(var task:plan.tasks())
                for(String arm:repeat==1?List.of("BASELINE","COMPARISON"):List.of("COMPARISON","BASELINE")) {
                    client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("utterance-direction-verification");long start=System.nanoTime();
                    boolean comparison=arm.equals("COMPARISON");
                    var response=client.completeAsJson(comparison?task.comparisonSystem():task.baselineSystem(),
                            requestInput(plan,task,comparison),VerificationResult.class).orElse(null);
                    var usage=client.videoUsage();cost+=usage.costUsd();
                    var row=new LinkedHashMap<String,Object>();row.put("caseId",task.id());row.put("arm",arm);row.put("repeat",repeat);
                    row.put("labelStatus",task.labelStatus());row.put("expectedDecision",task.expectedDecision());
                    row.put("assessment",assess(task,response,basis(plan)?comparison:null));row.put("calls",List.copyOf(client.calls));row.put("usage",usage);
                    row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);save(output,plan,args[2],rows,budget,cost,"RUNNING");
                    boolean ok=!budget.exhausted && client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;
                    client.endVideo();System.out.println("DIRECTION_TRIAL "+rows.size()+"/56 "+task.id()+" "+arm+" repeat="+repeat);
                    check(ok,"Provider/budget/usage failure; stop without retries of the study");
                }
            save(output,plan,args[2],rows,budget,cost,"COMPLETE");
        } catch(Exception ex) { save(output,plan,args[2],rows,budget,cost,"STOPPED");throw ex; }
    }
}
