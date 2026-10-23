package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Freshly approved current verification check, not an original-request or full-video replay. */
public final class CurrentContextReferenceTrial {
    static final String CONSENT="NEW_USD1_CURRENT_CONTEXT_TEXT_20261023";
    record Plan(String version,String model,double cap,int attempts,Map<String,String> hashes,
                List<DefaultVerificationPlan.Request> requests,double reserved) {}
    static Map<String,String> implementation() throws Exception {
        var hashes=new TreeMap<>(DevelopmentBoundaryReferenceTrial.implementation());
        hashes.put("currentTrial",ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of("src/test/java/com/example/oops/analyzer/CurrentContextReferenceTrial.java"))));
        return hashes;
    }
    static Plan prepare(Path root) throws Exception {
        var hashes=implementation();
        byte[] runtime=Files.readAllBytes(root.resolve("guidelines/runtime.json"));
        hashes.put("data.runtime",ReferenceOnlyTrial.sha(runtime));
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(new String(runtime,java.nio.charset.StandardCharsets.UTF_8)),true,"classpath:frozen.json",6000,3,3000,"",true);
        library.load();
        var requests=new ArrayList<DefaultVerificationPlan.Request>();
        for(String name:List.of("audience-devaluation","mt-preference","victim-quotation")) {
            byte[] unitBytes=Files.readAllBytes(root.resolve("context-units/mimiminu-"+name+".json"));
            var unit=JSON.readTree(unitBytes);
            var sourcePath=root.resolve(unit.at("/source/path").asText()).toRealPath();
            check(sourcePath.startsWith(root.toRealPath()),"Local source required");
            byte[] sourceBytes=Files.readAllBytes(sourcePath);
            check(ReferenceOnlyTrial.sha(sourceBytes).equals(unit.at("/source/sha256").asText()),"Source drift");
            var source=JSON.readTree(sourceBytes);
            check(source.at("/developmentReview/basis").asText().equals("USER_APPROVED_DEVELOPMENT_BOUNDARY"),"Prior user boundary required");
            String expected=unit.path("developmentDecision").asText();
            check(expected.equals(source.at("/developmentReview/decision").asText()),"Boundary drift");
            String anchor=name.equals("audience-devaluation")?"stt-index-50":name.equals("mt-preference")?"stt-index-321":"stt-index-4";
            var raw=source.get("segments");
            // For 99/109 preserve the original allowed verification window, not just selected evidence.
            if(!name.equals("victim-quotation")) {
                String folder=name.equals("audience-devaluation")?"quotation-ocr-followup-20261022-cZy7vL":"ocr-casebook-repeat-20261022-3nphCI";
                int video=name.equals("audience-devaluation")?99:109;
                var dir=root.resolve("experiments").resolve(folder);
                byte[] diagnostic=Files.readAllBytes(dir.resolve("diagnostics-"+video+".json"));
                byte[] transcript=Files.readAllBytes(dir.resolve("transcript-"+video+".json"));
                hashes.put("data.diagnostics."+video,ReferenceOnlyTrial.sha(diagnostic));
                hashes.put("data.transcript."+video,ReferenceOnlyTrial.sha(transcript));
                var candidates=JSON.readTree(diagnostic).at("/data/snapshot/analyzers/0/candidatePipeline/candidates");
                var allowed=new HashSet<String>();
                for(var c:candidates) if(c.path("anchorId").asText().equals(anchor)) c.get("contextSegmentIds").forEach(id->allowed.add(id.asText()));
                var rows=JSON.createArrayNode(); int i=0;
                for(var line:JSON.readTree(transcript).get("data")) {
                    String id="stt-index-"+i++;
                    if(allowed.contains(id)) { var copy=(tools.jackson.databind.node.ObjectNode)JSON.readTree(line.toString()); copy.put("id",id); rows.add(copy); }
                }
                check(rows.size()==allowed.size() && allowed.contains(anchor),"Exact saved allowed window required"); raw=rows;
            }
            var condition=new DevelopmentBoundaryReferenceTrial.Boundary(name,expected,anchor,raw,true);
            String input=DevelopmentBoundaryReferenceTrial.normalInput(condition);
            var segments=CandidateHypothesisTrial.raw(JSON.readTree(input)).segments();
            var selection=library.select(SPEECH,segments,name,segments.stream().filter(s->s.id().equals(anchor)).toList());
            check(selection.trace().exampleIds().contains(unit.path("id").asText()),"Current reference must reach verifier");
            hashes.put("data.unit."+name,ReferenceOnlyTrial.sha(unitBytes));
            hashes.put("data.source."+name,ReferenceOnlyTrial.sha(sourceBytes));
            hashes.put("data.selected."+name,String.join(",",selection.trace().exampleIds()));
            for(int repeat=1;repeat<=2;repeat++) requests.add(new DefaultVerificationPlan.Request(name,
                    "EXISTING_USER_APPROVED_DEVELOPMENT_BOUNDARY",expected,"CURRENT",repeat,
                    CandidateReviewEngine.verificationPrompt(0)+selection.prompt(),input));
        }
        var plan=new Plan("current-context-verification-1","gpt-6-luna",1.0,6,hashes,List.copyOf(requests),DefaultVerificationPlan.reservation(requests,"gpt-6-luna"));
        validate(plan);return plan;
    }
    static void validate(Plan plan) {
        check(plan.version().equals("current-context-verification-1") && plan.cap()==1.0 && plan.attempts()==6
                && plan.requests().size()==6 && plan.model().equals("gpt-6-luna")
                && Double.isFinite(plan.reserved()) && plan.reserved()>0 && plan.reserved()<=plan.cap(),"Bounded six-call plan required");
        var seen=new HashSet<String>();
        for(var r:plan.requests()) {
            check(Set.of("audience-devaluation","mt-preference","victim-quotation").contains(r.caseId())
                    && r.arm().equals("CURRENT") && r.repeat()>=1 && r.repeat()<=2 && seen.add(r.caseId()+r.repeat()),"Unique fixed cases required");
            var input=JSON.readTree(r.input());
            check(!input.has("expectedDecision") && !input.has("labelStatus") && input.path("candidates").size()==1,"No label leakage");
        }
    }
    public static void main(String[] args) throws Exception {
        check(args.length==2 && args[0].equals("prepare") || args.length==4 && args[0].equals("run"),"prepare NEW-directory OR run plan sha NEW-directory");
        Path root=Path.of("../datasets/controversy").toRealPath(),out=Path.of(args[args.length-1]).toAbsolutePath();
        check(!Files.exists(out) && out.getParent().toRealPath().startsWith(root.resolve("experiments")),"New private output required");
        if(args[0].equals("prepare")) {
            var p=prepare(root);byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(out);Files.write(out.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("PREPARED calls=6 reserve="+p.reserved()+" sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));check(ReferenceOnlyTrial.sha(bytes).equals(args[2]),"Plan hash mismatch");
        var p=JSON.readValue(bytes,Plan.class);validate(p);
        check(CONSENT.equals(System.getenv("CURRENT_CONTEXT_TRIAL_CONSENT")),"Fresh consent required");
        check(prepare(root).hashes().equals(p.hashes()),"Source or implementation drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),out,args[2],CONSENT,6);
        Files.createDirectory(out);Files.write(out.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var budget=new TextBoundaryTrial.Budget(1.0,.10,.50,out);var guard=new DefaultVerificationTrial.RequestGuard();
        var props=new OpenAiProperties(key,null,null,"https://api.openai.com/v1",p.model(),"none",Duration.ofSeconds(60),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
        var rest=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                .requestInterceptor((req,body,execution)->{byte[] approved=guard.accept(body,req.getURI().toString());try{check(budget.attempts<6,"Six HTTP attempts maximum");budget.reserve(approved.length);}catch(Exception e){throw new IllegalArgumentException("Budget guard",e);}req.getHeaders().setContentLength(approved.length);return execution.execute(req,approved);}).build();
        var client=new MechanismRoutingTrial.Client(rest,props);var rows=new ArrayList<Map<String,Object>>();double cost=0;
        try {
            for(var r:p.requests()) {
                client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("current-context-check");
                guard.begin(JSON.writeValueAsBytes(client.replayRequestBody(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class)));
                var response=client.completeAsJson(r.system(),r.input(),CandidateReviewEngine.VerificationResult.class).orElse(null);
                var usage=client.videoUsage();cost+=usage.costUsd();
                rows.add(Map.of("caseId",r.caseId(),"repeat",r.repeat(),"assessment",DefaultVerificationPlan.assess(r,response),"calls",List.copyOf(client.calls),"usage",usage));
                save(out,rows,budget,cost,"RUNNING");boolean ok=client.failureCount()==0&&usage.calls()==1&&client.calls.size()==1;client.endVideo();
                System.out.println("CURRENT_CONTEXT "+rows.size()+"/6 "+r.caseId());check(ok,"Provider failure; no retry");
            }
            save(out,rows,budget,cost,"COMPLETE");
        } catch(Exception e) {save(out,rows,budget,cost,"STOPPED");throw e;}
    }
    static void save(Path out,List<Map<String,Object>> rows,TextBoundaryTrial.Budget budget,double cost,String state) throws Exception {
        Files.writeString(out.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"results",rows,"httpAttempts",budget.attempts,
                "reservedEstimatedUsd",budget.reserved,"usageEstimatedUsd",cost,"approvedCapUsd",1.0,"actualBillingConfirmed",false,
                "scope","CURRENT_RECONSTRUCTED_VERIFICATION_ONLY_NOT_FULL_PIPELINE")));
    }
}
