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

/** Explicitly approved six/16-call reference comparisons. Never invoked by JUnit or the application. */
public final class DevelopmentBoundaryReferenceTrial {
    static final String CONSENT="NEW_USD025_V99_REFERENCE_TEXT_APPROVED_20261022";
    static final String VERSION="v99-development-reference-frozen-1";
    static final String NORMAL_VERSION="audience-normal-reference-frozen-1";
    static final String NORMAL_CONSENT="NEW_USD025_NORMAL16_TEXT_APPROVED_20261022";
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
    record Boundary(String id,String expected,String anchor,tools.jackson.databind.JsonNode raw,boolean limited) {}
    static Plan normalPlan(byte[] source,byte[] before,byte[] after,Path root) throws Exception {
        var draft=JSON.readTree(source);
        check("USER_APPROVED_DEVELOPMENT_BOUNDARIES".equals(draft.path("status").asText())
                && draft.at("/approval/newEstimatedCapUsd").asDouble()==.25
                && draft.at("/approval/maxHttpAttempts").asInt()==16,"Fresh normal label and execution approval required");
        var conditions=new ArrayList<Boundary>();var metadata=implementation();
        for(var c:draft.path("normalDrafts")) {
            check(c.path("humanApproved").asBoolean() && "PASS".equals(c.path("expectedDecision").asText())
                    && "SYNTHETIC_DEVELOPMENT_CONTROL".equals(c.path("sourceKind").asText()),"Approved synthetic normal required");
            conditions.add(new Boundary(c.path("id").asText(),"PASS",c.path("anchorId").asText(),c.get("raw"),false));
        }
        for(var c:draft.path("retainedApprovedBoundaries")) {
            Path path=root.resolve(c.path("source").asText()).toRealPath();
            check(path.startsWith(root.toRealPath()),"Local source must remain within dataset");
            byte[] bytes=OfflineDecisionContractAudit.bounded(path);var saved=JSON.readTree(bytes);
            String id=c.path("id").asText(),expected=c.path("expectedDecision").asText();
            check("PRIOR_USER_APPROVED_DEVELOPMENT_BOUNDARY".equals(c.path("basis").asText()),"Retained user boundary required");
            boolean audience=id.equals("actual-audience-review");
            check(audience ? saved.path("videoId").asInt()==99 && expected.equals("REVIEW_REQUIRED")
                    : id.equals("actual-victim-quotation") && expected.equals("PASS")
                    && saved.at("/developmentReview/decision").asText().equals("PASS"),"Known source/decision required");
            conditions.add(new Boundary(id,expected,audience?saved.path("anchorId").asText():"stt-index-4",
                    saved.get(audience?"raw":"segments"),audience));
            metadata.put("meta.source."+id,ReferenceOnlyTrial.sha(bytes));
        }
        metadata.put("meta.beforeRuntime",ReferenceOnlyTrial.sha(before));
        metadata.put("meta.afterRuntime",ReferenceOnlyTrial.sha(after));
        var libraries=new ArrayList<ReviewGuidelineLibrary>();
        for(var bytes:List.of(before,after)) {
            var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),
                    true,"classpath:frozen.json",6000,3,3000,"",true);
            library.load();check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),"Ready archive required");
            libraries.add(library);
        }
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        for(var c:conditions) {
            var input=normalInput(c);var raw=CandidateHypothesisTrial.raw(JSON.readTree(input)).segments();
            var focus=raw.stream().filter(s->s.id().equals(c.anchor())).toList();var systems=new HashMap<String,String>();
            for(int i=0;i<2;i++) {
                String arm=i==0?"BEFORE":"AFTER";
                var selected=libraries.get(i).select(TimelineEventType.SPEECH,raw,c.id(),focus);
                metadata.put("meta.selected."+c.id()+"."+arm,String.join(",",selected.trace().exampleIds()));
                systems.put(arm,CandidateReviewEngine.verificationPrompt(0)+selected.prompt());
            }
            for(int repeat=1;repeat<=2;repeat++) for(String arm:repeat==1?List.of("BEFORE","AFTER"):List.of("AFTER","BEFORE"))
                requests.add(new DefaultVerificationPlan.Request(c.id(),"USER_APPROVED_DEVELOPMENT_BOUNDARY",c.expected(),arm,repeat,systems.get(arm),input));
        }
        var p=new Plan(NORMAL_VERSION,"gpt-6-luna",.25,16,ReferenceOnlyTrial.sha(source),
                ReferenceOnlyTrial.sha((ReferenceOnlyTrial.sha(before)+ReferenceOnlyTrial.sha(after)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                metadata,List.copyOf(requests),DefaultVerificationPlan.reservation(requests,"gpt-6-luna"));
        validate(p);return p;
    }
    static String normalInput(Boundary c) {
        var wrapper=JSON.createObjectNode();wrapper.set("raw",c.raw());
        var raw=CandidateHypothesisTrial.raw(wrapper).segments();
        check(!raw.isEmpty() && raw.size()<=48,"Bounded raw required");
        var anchor=raw.stream().filter(s->s.id().equals(c.anchor())).findFirst().orElseThrow();
        return JSON.writeValueAsString(Map.of("promptRevision",CandidateReviewEngine.REVISION,"raw",c.raw(),
                "candidates",List.of(Map.of("candidateId","candidate-1","anchorId",c.anchor(),"axis","TARGET_TREATMENT",
                "hypothesisNotEvidence","재구성 입력 연결 확인용 후보이며 당시 탐색 가설이 아니다.",
                "proposedEvidence",List.of(Map.of("segmentId",c.anchor(),"quote",anchor.text())),
                "contextExpanded",false,"contextLimited",c.limited(),"segmentIds",raw.stream().map(ReviewInput.Segment::id).toList()))));
    }
    static void validateNormal(Plan p) {
        var expected=Map.of("product-duration-guidance","PASS","study-restart-encouragement","PASS",
                "actual-audience-review","REVIEW_REQUIRED","actual-victim-quotation","PASS");
        check(p.maxAttempts()==16 && p.requests().size()==16 && p.cap()==.25 && "gpt-6-luna".equals(p.model())
                && Double.isFinite(p.reservationEstimatedUsd()) && p.reservationEstimatedUsd()>0 && p.reservationEstimatedUsd()<=.25,
                "Fixed 16-call budget required");
        var inputs=new HashMap<String,String>();var systems=new HashMap<String,String>();var seen=new HashSet<String>();
        for(var r:p.requests()) {
            check(expected.containsKey(r.caseId()) && expected.get(r.caseId()).equals(r.expectedDecision())
                    && Set.of("BEFORE","AFTER").contains(r.arm()) && r.repeat()>=1 && r.repeat()<=2
                    && seen.add(r.caseId()+":"+r.arm()+":"+r.repeat()),"Unique approved condition/arm/repeat required");
            var old=inputs.putIfAbsent(r.caseId(),r.input());check(old==null || old.equals(r.input()),"Only references may differ");
            var system=systems.putIfAbsent(r.caseId()+":"+r.arm(),r.system());
            check((system==null || system.equals(r.system())) && r.system().startsWith(CandidateReviewEngine.verificationPrompt(0)),"Common fixed judgment required");
            var input=JSON.readTree(r.input());CandidateHypothesisTrial.raw(input);
            check(!input.has("expectedDecision") && !input.has("labelStatus") && input.path("candidates").size()==1,"No input answer fields");
        }
    }
    static TreeMap<String,String> implementation() throws Exception {
        var result=new TreeMap<>(V7RoutingVideoTrial.implementation());
        result.put("boundaryTrial",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/DevelopmentBoundaryReferenceTrial.java"))));
        return result;
    }
    static void validate(Plan p) {
        if(NORMAL_VERSION.equals(p.schemaVersion())) {validateNormal(p);return;}
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
        reserve(budget,bytes,6);
    }
    static void reserve(TextBoundaryTrial.Budget budget,int bytes,int limit) throws Exception {
        check(limit==6 || limit==16,"Known approved request cap required");
        check(budget.attempts<limit,limit==6?"Six attempts maximum":"Sixteen attempts maximum");budget.reserve(bytes);
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
            byte[] source=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
            byte[] before=OfflineDecisionContractAudit.bounded(Path.of(args[2])),after=OfflineDecisionContractAudit.bounded(Path.of(args[3]));
            var p="audience-boundary-draft-1".equals(JSON.readTree(source).path("schemaVersion").asText())
                    ? normalPlan(source,before,after,Path.of("../datasets/controversy").toRealPath()) : plan(source,before,after);
            byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("PREPARED providerCalls=0 attempts="+p.maxAttempts()+" reservation="+p.reservationEstimatedUsd()+" sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Frozen plan hash mismatch");
        var p=JSON.readValue(bytes,Plan.class);validate(p);
        String consent=NORMAL_VERSION.equals(p.schemaVersion())?NORMAL_CONSENT:CONSENT;
        check(args[0].equals("run") && consent.equals(System.getenv("BOUNDARY_REFERENCE_TRIAL_CONSENT")),"Fresh explicit consent required");
        var implementation=implementation();
        for(String k:List.of("beforeRuntime","afterRuntime","beforeSelected","afterSelected")) implementation.put(k,p.implementation().get(k));
        implementation.values().removeIf(Objects::isNull);
        p.implementation().forEach((k,v)->{if(k.startsWith("meta.")) implementation.put(k,v);});
        check(p.implementation().equals(implementation)
                && Double.compare(p.reservationEstimatedUsd(),DefaultVerificationPlan.reservation(p.requests(),p.model()))==0,"Implementation or reservation drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null && !key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),output,args[2],consent,p.maxAttempts());
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var budget=new TextBoundaryTrial.Budget(.25,.10,.50,output);var guard=new DefaultVerificationTrial.RequestGuard();
        var props=new OpenAiProperties(key,null,null,"https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(60),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((req,body,execution)-> {
                    byte[] approved=guard.accept(body,req.getURI().toString());
                    try {reserve(budget,approved.length,p.maxAttempts());} catch(Exception ex) {throw new IllegalArgumentException("Budget guard failed",ex);}
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
                System.out.println("PILOT "+rows.size()+"/"+p.maxAttempts()+" "+r.caseId()+" "+r.arm());check(ok,"Provider failure: stop without retry");
            }
            check(budget.attempts==p.maxAttempts(),"HTTP accounting mismatch");save(output,p,args[2],rows,budget,cost,"COMPLETE");
            System.out.println("COMPLETE usageEstimate="+cost+" reserved="+budget.reserved);
        } catch(Exception ex) {save(output,p,args[2],rows,budget,cost,"STOPPED");throw ex;}
    }
}
