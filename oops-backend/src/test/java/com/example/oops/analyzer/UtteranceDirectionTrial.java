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
    static boolean direct(Plan plan) { return DirectTargetWritingComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean neutral(Plan plan) { return NeutralHypothesisComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean evidenceContext(Plan plan) { return EvidenceContextComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean situationFirst(Plan plan) { return SituationFirstComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean understanding(Plan plan) { return SourceUnderstandingComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean relations(Plan plan) { return SourceRelationsComparison.VERSION.equals(plan.schemaVersion()); }
    static boolean understandingStudy(Plan plan) { return understanding(plan) || relations(plan); }
    static int maxAttempts(Plan plan) { return understandingStudy(plan)?57:evidenceContext(plan)?32:MAX_HTTP_ATTEMPTS; }
    record Slot(Task task,String arm,int repeat) {}
    static List<Slot> schedule(Plan plan) {
        var slots=new ArrayList<Slot>();
        if(understandingStudy(plan)) slots.add(new Slot(SourceUnderstandingComparison.smoke(plan),"COMPARISON",0));
        for(int repeat=1;repeat<=2;repeat++) for(var task:plan.tasks()) for(String arm:arms(plan,task,repeat))
            slots.add(new Slot(task,arm,repeat));
        return List.copyOf(slots);
    }
    static List<String> arms(Plan plan,Task task,int repeat) {
        check(repeat==1 || repeat==2,"Two repeats only");
        if(evidenceContext(plan) && !EvidenceContextComparison.TARGETS.contains(task.id())) return List.of("BASELINE");
        return repeat==1?List.of("BASELINE","COMPARISON"):List.of("COMPARISON","BASELINE");
    }
    static void consent(Plan plan,String mode,String consent) {
        check(basis(plan)==mode.equals("run-basis") && direct(plan)==mode.equals("run-direct-writing")
                        && neutral(plan)==mode.equals("run-neutral-hypothesis")
                        && evidenceContext(plan)==mode.equals("run-evidence-context")
                        && situationFirst(plan)==mode.equals("run-situation-first")
                        && understanding(plan)==mode.equals("run-understanding")
                        && relations(plan)==mode.equals("run-relations-v2")
                        && (relations(plan)?SourceRelationsComparison.CONSENT:understanding(plan)?SourceUnderstandingComparison.CONSENT:situationFirst(plan)?SituationFirstComparison.CONSENT:evidenceContext(plan)?EvidenceContextComparison.CONSENT:neutral(plan)?NeutralHypothesisComparison.CONSENT:direct(plan)?DirectTargetWritingComparison.CONSENT:basis(plan)?BASIS_CONSENT:CONSENT).equals(consent),
                "Selected contract and fresh cost/text approval required");
    }
    static Plan directPlan(byte[] factorialBytes,byte[] historyBytes) throws Exception {
        var source=JSON.readValue(factorialBytes,VerificationFactorialPlan.Plan.class);
        var history=JSON.readValue(historyBytes,Plan.class);validate(history);
        check(basis(history) && ReferenceOnlyTrial.sha(historyBytes).equals(source.sourcePlanSha256())
                && VerificationFactorialPlan.VERSION.equals(source.schemaVersion()) && source.requests().size()==56,
                "Frozen factorial source/history mismatch");
        var tasks=new ArrayList<Task>();
        for(var r:source.requests()) if(r.arm()==VerificationFactorialPlan.Arm.BASELINE) {
            var original=history.tasks().stream().filter(t -> t.id().equals(r.caseId())).findFirst().orElseThrow();
            check(original.baselineSystem().equals(r.system()) && original.input().equals(r.input()),"Frozen baseline input/system mismatch");
            tasks.add(new Task(r.caseId(),r.labelStatus(),r.expectedDecision(),null,null,r.system(),
                    DirectTargetWritingComparison.comparison(r.system()),r.input()));
        }
        var plan=new Plan(DirectTargetWritingComparison.VERSION,source.model(),2,.50,ReferenceOnlyTrial.sha(factorialBytes),
                history.runtimeSha256(),history.normalFixtureSha256(),history.relationFixtureSha256(),List.copyOf(tasks));
        validate(plan);return plan;
    }
    static Plan basisPlan(Plan original) throws Exception {
        validate(original);
        var tasks=original.tasks().stream().map(t -> new Task(t.id(),t.labelStatus(),t.expectedDecision(),
                t.relationDraft(),t.unsupportedInference(),t.baselineSystem(),FinalReviewBasisContract.prompt(t.baselineSystem()),t.input())).toList();
        var plan=new Plan(BASIS_REVISION,original.model(),original.repeats(),original.estimatedCapUsd(),original.sourceSha256(),
                original.runtimeSha256(),original.normalFixtureSha256(),original.relationFixtureSha256(),tasks);
        validate(plan);return plan;
    }
    static String requestInput(Plan plan,Task task,boolean comparison) {
        if(evidenceContext(plan) && comparison) return EvidenceContextComparison.input(task.id(),task.input());
        if(neutral(plan) && comparison) return NeutralHypothesisComparison.input(task.input());
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
        check((UtteranceDirectionPolicy.REVISION.equals(plan.schemaVersion()) || basis(plan) || direct(plan) || neutral(plan) || evidenceContext(plan) || situationFirst(plan) || understandingStudy(plan)) && "gpt-6-luna".equals(plan.model())
                && plan.repeats()==2 && plan.estimatedCapUsd()==.50 && plan.tasks()!=null && plan.tasks().size()==14
                && plan.tasks().stream().map(Task::id).distinct().count()==14,"Known 56-call plan required");
        check(ReferenceOnlyTrial.sha(fixture("/structured-search-boundaries.json")).equals(plan.normalFixtureSha256())
                && ReferenceOnlyTrial.sha(fixture("/utterance-direction-boundaries.json")).equals(plan.relationFixtureSha256()),"Boundary fixtures changed");
        for(var task:plan.tasks()) {
            check((relations(plan)?SourceUnderstandingRelations.fromV1Prompt(task.baselineSystem()):understanding(plan)?SourceUnderstandingContract.fromOrderedPrompt(task.baselineSystem()):situationFirst(plan)?SituationFirstVerification.prompt(task.baselineSystem()):neutral(plan)||evidenceContext(plan)?task.baselineSystem():direct(plan)?DirectTargetWritingComparison.comparison(task.baselineSystem()):basis(plan)?FinalReviewBasisContract.prompt(task.baselineSystem()):UtteranceDirectionPolicy.comparison(task.baselineSystem()))
                    .equals(task.comparisonSystem()),"Comparison changes only direction guidance or selected final basis contract");
            var input=JSON.readTree(task.input());
            check(input.path("raw").isArray() && !input.path("raw").isEmpty() && input.path("raw").size()<=48
                    && input.path("candidates").size()==1 && !input.has("repair") && !input.has("expectedDecision")
                    && !input.has("relationDraft") && !input.has("unsupportedInference") && !input.has("verificationContract"),"Fixed unlabeled verification input required");
        }
        if(neutral(plan)) plan.tasks().forEach(t -> NeutralHypothesisComparison.input(t.input()));
        if(evidenceContext(plan)) {
            check(plan.tasks().stream().filter(t -> EvidenceContextComparison.TARGETS.contains(t.id())).count()==2,"Two fixed intervention cases required");
            plan.tasks().forEach(t -> EvidenceContextComparison.input(t.id(),t.input()));
        }
        if(direct(plan) || neutral(plan) || evidenceContext(plan) || situationFirst(plan) || understandingStudy(plan)) {
            check(plan.tasks().stream().filter(t -> t.expectedDecision()!=null).count()==8
                    && plan.tasks().stream().filter(t -> "PASS".equals(t.expectedDecision())).count()==6
                    && plan.tasks().stream().filter(t -> "REVIEW_REQUIRED".equals(t.expectedDecision())).count()==2,
                    "Eight approved boundaries and six unlabeled drafts required");
        }
        check(reservationEstimate(plan)<=plan.estimatedCapUsd(),"Frozen request reservation exceeds fresh cap");
    }
    static double reservationEstimate(Plan plan) {
        var props=new OpenAiProperties("offline-test-key",null,null,"https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(2),null);
        var client=new com.example.oops.client.OpenAiClient(RestClient.create(),props);
        double total=0;
        for(var slot:schedule(plan)) {
            var task=slot.task();String arm=slot.arm();
            boolean comparison=arm.equals("COMPARISON");
            var body=JSON.writeValueAsBytes(client.replayRequestBody(comparison?task.comparisonSystem():task.baselineSystem(),
                    requestInput(plan,task,comparison),VerificationResult.class));
            var guarded=V7RoutingVideoTrial.guardBody(body,"https://api.openai.com/v1/chat/completions");
            total+=(guarded.length+1024)*.10/1_000_000+8000*.50/1_000_000;
        }
        return total;
    }
    static void reserve(StructuredSearchBoundaryTrial.Budget budget,int bytes) throws Exception {
        reserve(budget,bytes,MAX_HTTP_ATTEMPTS);
    }
    static void reserve(StructuredSearchBoundaryTrial.Budget budget,int bytes,int maxAttempts) throws Exception {
        check(maxAttempts==32 || maxAttempts==MAX_HTTP_ATTEMPTS || maxAttempts==57,"Known HTTP cap required");
        if(budget.attempts>=maxAttempts) { budget.exhausted=true;throw new IllegalArgumentException(maxAttempts+" HTTP attempt cap reached"); }
        budget.reserve(bytes);
        Files.writeString(budget.output.resolve("reservation.json"),JSON.writeValueAsString(Map.of(
                "newEstimatedCapUsd",budget.cap,"httpAttempts",budget.attempts,"maxHttpAttempts",maxAttempts,
                "reservedEstimatedUsd",budget.reserved,"inputPer1m",.10,"outputPer1m",.50,"actualBillingConfirmed",false)));
    }
    static Map<String,Object> assess(Task task,VerificationResult response) {
        return assess(task,response,null);
    }
    static Map<String,Object> assess(Task task,VerificationResult response,Boolean finalBasisEnabled) {
        return assess(task,response,finalBasisEnabled,null);
    }
    static Map<String,Object> assess(Task task,VerificationResult response,Boolean finalBasisEnabled,Boolean understandingEnabled) {
        return assess(task,response,finalBasisEnabled,understandingEnabled,SourceUnderstandingContract.Version.ACTS_V1);
    }
    static Map<String,Object> assess(Plan plan,Task task,VerificationResult response,boolean comparison) {
        return assess(task,response,basis(plan)?comparison:null,
                relations(plan)?Boolean.TRUE:understanding(plan)?comparison:null,
                relations(plan) && comparison?SourceUnderstandingContract.Version.RELATIONS_V2:SourceUnderstandingContract.Version.ACTS_V1);
    }
    static Map<String,Object> assess(Task task,VerificationResult response,Boolean finalBasisEnabled,Boolean understandingEnabled,
                                     SourceUnderstandingContract.Version version) {
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
            v=understandingEnabled!=null?SourceUnderstandingContract.validate(candidate,response.verifications().get(0),raw,0,false,understandingEnabled,version)
                    :finalBasisEnabled==null?CandidateReviewEngine.validate(candidate,response.verifications().get(0).assessment(),raw,0)
                    :FinalReviewBasisContract.validate(candidate,response.verifications().get(0),raw,0,finalBasisEnabled);
        var row=new LinkedHashMap<String,Object>();row.put("accepted",v.observation()!=null);
        row.put("failureCode",v.failureCode());row.put("observation",v.observation());row.put("evidenceMeaningReview","NOT_AUTOMATICALLY_ASSESSED");
        return row;
    }
    static void save(Path output,Plan plan,String sha,List<Map<String,Object>> rows,StructuredSearchBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("schemaVersion",plan.schemaVersion(),
                "planSha256",sha,"state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"actualBillingConfirmed",false,"maxHttpAttempts",maxAttempts(plan))));
    }
    public static void main(String[] args) throws Exception {
        check(args.length>=1 && (Set.of("prepare","prepare-basis","prepare-direct-writing","prepare-neutral-hypothesis","prepare-evidence-context","prepare-situation-first","prepare-understanding","prepare-relations-v2").contains(args[0]) && args.length==4
                        || Set.of("run","run-basis","run-direct-writing","run-neutral-hypothesis","run-evidence-context","run-situation-first","run-understanding","run-relations-v2").contains(args[0]) && args.length==5),
                "prepare saved-results runtime NEW-dir OR run plan approved-sha fresh-consent NEW-dir");
        Path output=Path.of(args[args.length-1]).toAbsolutePath().normalize();
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].startsWith("prepare")) {
            byte[] sourceBytes=OfflineDecisionContractAudit.bounded(Path.of(args[1])),secondBytes=OfflineDecisionContractAudit.bounded(Path.of(args[2]));
            if(args[0].equals("prepare-direct-writing")) check(ReferenceOnlyTrial.sha(sourceBytes).equals(DirectTargetWritingComparison.SOURCE_SHA),"Approved historical factorial source required");
            if(args[0].equals("prepare-neutral-hypothesis")) check(ReferenceOnlyTrial.sha(sourceBytes).equals(NeutralHypothesisComparison.SOURCE_SHA)
                    && ReferenceOnlyTrial.sha(secondBytes).equals(NeutralHypothesisComparison.RESULTS_SHA),"Frozen neutral comparison source/results required");
            if(args[0].equals("prepare-evidence-context")) check(ReferenceOnlyTrial.sha(sourceBytes).equals(EvidenceContextComparison.SOURCE_SHA)
                    && ReferenceOnlyTrial.sha(secondBytes).equals(EvidenceContextComparison.RESULTS_SHA),"Frozen evidence comparison source/results required");
            var plan=args[0].equals("prepare-relations-v2")?SourceRelationsComparison.prepare(sourceBytes,secondBytes)
                    :args[0].equals("prepare-understanding")?SourceUnderstandingComparison.prepare(sourceBytes,secondBytes)
                    :args[0].equals("prepare-situation-first")?SituationFirstComparison.prepare(sourceBytes,secondBytes)
                    :args[0].equals("prepare-evidence-context")?EvidenceContextComparison.prepare(sourceBytes,secondBytes)
                    :args[0].equals("prepare-neutral-hypothesis")?NeutralHypothesisComparison.prepare(sourceBytes,secondBytes)
                    :args[0].equals("prepare-direct-writing")?directPlan(sourceBytes,secondBytes):prepare(sourceBytes,secondBytes);
            if(args[0].equals("prepare-basis")) plan=basisPlan(plan);
            Files.createDirectory(output);byte[] bytes=JSON.writeValueAsBytes(plan);
            Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("DIRECTION_PREPARED tasks=14 calls="+maxAttempts(plan)+" providerCalls=0 reservation="+reservationEstimate(plan)+" planSha256="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan required");
        var plan=JSON.readValue(bytes,Plan.class);validate(plan);
        consent(plan,args[0],args[3]);
        if(direct(plan)) check(DirectTargetWritingComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Approved direct-writing source hash required");
        if(neutral(plan)) check(NeutralHypothesisComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Frozen neutral source hash required");
        if(evidenceContext(plan)) check(EvidenceContextComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Frozen evidence source hash required");
        if(situationFirst(plan)) check(SituationFirstComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Frozen situation-first source hash required");
        if(understanding(plan)) check(SourceUnderstandingComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Frozen understanding source hash required");
        if(relations(plan)) check(SourceRelationsComparison.SOURCE_SHA.equals(plan.sourceSha256()),"Frozen v1 source hash required");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("authorization.json"),JSON.writeValueAsString(Map.of("newEstimatedCapUsd",.50,
                "consent",args[3],"planSha256",args[2],"textEgressApproved",true,"audioVideoEgressApproved",false,"previousBudgetReused",false)));
        var budget=new StructuredSearchBoundaryTrial.Budget(output,.50);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1",plan.model(),"none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(90));
        var builder=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((request,body,execution) -> {
                    byte[] guarded=V7RoutingVideoTrial.guardBody(body,request.getURI().toString());
                    try { reserve(budget,guarded.length,maxAttempts(plan)); } catch(java.io.IOException ex) { throw ex; }
                    catch(Exception ex) { throw new IllegalArgumentException("Reservation failed",ex); }
                    request.getHeaders().setContentLength(guarded.length);return execution.execute(request,guarded);
                });
        if(props.hasOrganization()) builder.defaultHeader("OpenAI-Organization",props.organization());
        if(props.hasProject()) builder.defaultHeader("OpenAI-Project",props.project());
        var client=new MechanismRoutingTrial.Client(builder.build(),props);
        var rows=new ArrayList<Map<String,Object>>();double cost=0;save(output,plan,args[2],rows,budget,cost,"RUNNING");
        try {
            for(var slot:schedule(plan)) {
                    var task=slot.task();String arm=slot.arm();int repeat=slot.repeat();
                    client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("utterance-direction-verification");long start=System.nanoTime();
                    boolean comparison=arm.equals("COMPARISON");
                    var response=client.completeAsJson(comparison?task.comparisonSystem():task.baselineSystem(),
                            requestInput(plan,task,comparison),VerificationResult.class).orElse(null);
                    var usage=client.videoUsage();cost+=usage.costUsd();
                    var row=new LinkedHashMap<String,Object>();row.put("caseId",task.id());row.put("arm",arm);row.put("repeat",repeat);
                    row.put("labelStatus",task.labelStatus());row.put("expectedDecision",task.expectedDecision());
                    var actualTask=new Task(task.id(),task.labelStatus(),task.expectedDecision(),null,null,task.baselineSystem(),
                            task.comparisonSystem(),requestInput(plan,task,comparison));
                    var assessment=assess(plan,actualTask,response,comparison);
                    row.put("assessment",assessment);row.put("calls",List.copyOf(client.calls));row.put("usage",usage);
                    row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);save(output,plan,args[2],rows,budget,cost,"RUNNING");
                    boolean ok=!budget.exhausted && client.failureCount()==0 && usage.calls()==1 && client.calls.size()==1;
                    client.endVideo();System.out.println("DIRECTION_TRIAL "+rows.size()+"/"+maxAttempts(plan)+" "+task.id()+" "+arm+" repeat="+repeat);
                    check(ok,"Provider/budget/usage failure; stop without retries of the study");
                    if(understandingStudy(plan)) SourceUnderstandingComparison.gate(slot,assessment);
                }
            check(rows.size()==maxAttempts(plan) && budget.attempts==maxAttempts(plan),"Incomplete HTTP accounting; no completion");
            save(output,plan,args[2],rows,budget,cost,"COMPLETE");
        } catch(Exception ex) { save(output,plan,args[2],rows,budget,cost,"STOPPED");throw ex; }
    }
}
