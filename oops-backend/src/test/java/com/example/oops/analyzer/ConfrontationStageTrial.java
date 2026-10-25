package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Opt-in text diagnostic. Preparation/tests never load credentials or contact a provider. */
public final class ConfrontationStageTrial {
    static final String CONSENT="NEW_USD050_CONFRONTATION_STAGE_TEXT_APPROVED";
    static final String ENDPOINT="https://api.openai.com/v1/chat/completions";
    static final int MAX_BYTES=120_000, MAX_OUTPUT=8000, MAX_PER_MODE=4, MAX_ATTEMPTS=24;
    static final double CAP=.50, INPUT_RATE=.10, OUTPUT_RATE=.50;
    record Seed(String anchorId,String axis,String reason,List<String> evidenceIds) {}
    record Condition(String id,String kind,String expectedDecision,List<ReviewInput.Segment> raw,Seed seed) {}
    record Frozen(String caseId,String mode,String system,String input,String kind,String bodySha256) {}
    record Plan(String version,String model,String sourcePlanPath,String sourcePlanSha256,String runtime,
                String runtimeSha256,Map<String,String> implementation,List<Condition> cases,
                List<Frozen> requests,double worstReservedUsd) {}
    static tools.jackson.databind.JsonNode ordered(tools.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            var out=JSON.createObjectNode();var entries=new TreeMap<String,tools.jackson.databind.JsonNode>();
            node.properties().forEach(e -> entries.put(e.getKey(),e.getValue()));
            entries.forEach((k,v)->out.set(k,ordered(v)));return out;
        }
        if(node.isArray()) {var out=JSON.createArrayNode();node.forEach(n->out.add(ordered(n)));return out;}
        return node;
    }
    static String canonical(String json){return JSON.writeValueAsString(ordered(JSON.readTree(json)));}
    static String system(String system) {
        String marker="contextReference=";int at=system.indexOf(marker);
        return at<0?system:system.substring(0,at+marker.length())+canonical(system.substring(at+marker.length()));
    }
    static double reservation(int bytes) { return (bytes+1024)*INPUT_RATE/1_000_000+MAX_OUTPUT*OUTPUT_RATE/1_000_000; }
    static OpenAiProperties props(String key) {
        return new OpenAiProperties(key,null,null,"https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(60),
                new OpenAiProperties.Pricing(INPUT_RATE,.01,OUTPUT_RATE,null,null));
    }
    static Map<String,String> implementation() throws Exception {
        var paths=new ArrayList<Path>();
        try(var stream=Files.walk(Path.of("src/main"))) {
            paths.addAll(stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")||p.toString().endsWith(".json")).toList());
        }
        for(String file:List.of("build.gradle","src/test/java/com/example/oops/analyzer/ConfrontationStageTrial.java",
                "src/test/java/com/example/oops/analyzer/MechanismRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/V7RoutingVideoTrial.java",
                "src/test/java/com/example/oops/analyzer/ReferenceOnlyTrial.java",
                "src/test/java/com/example/oops/analyzer/DefaultVerificationTrial.java",
                "src/test/java/com/example/oops/analyzer/TextBoundaryTrial.java",
                "src/test/java/com/example/oops/analyzer/OfflineDecisionContractAudit.java")) paths.add(Path.of(file));
        var hashes=new TreeMap<String,String>();
        for(var path:paths)hashes.put(path.toString(),ReferenceOnlyTrial.sha(Files.readAllBytes(path)));
        return hashes;
    }
    static ReviewGuidelineLibrary library(String runtime) {
        var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime),true,"classpath:frozen.json",6000,3,3000,"",true);
        library.load();return library;
    }
    static void validate(Condition c) {
        check(Set.of("PASS","REVIEW_REQUIRED").contains(c.expectedDecision())&&!c.raw().isEmpty()&&c.raw().size()<=64,"Bounded approved case required");
        new ReviewInput(c.raw());
        var ids=c.raw().stream().map(ReviewInput.Segment::id).toList();var seed=c.seed();
        check(seed.axis().equals("EXPRESSION_CONTENT")&&ids.contains(seed.anchorId())
                &&seed.evidenceIds().contains(seed.anchorId())&&seed.evidenceIds().size()<=8
                &&!seed.evidenceIds().isEmpty()&&new HashSet<>(seed.evidenceIds()).size()==seed.evidenceIds().size()
                &&ids.containsAll(seed.evidenceIds()),"Seed must use current source IDs including anchor");
        check(DialogueReadingPage.plan(new ReviewInput(c.raw())).size()==1,"One uncut reading page required");
    }
    static CandidateReviewEngine.Discovery seed(Condition c) {
        var quotes=c.seed().evidenceIds().stream().map(id -> new CandidateReviewEngine.Quote(id,
                c.raw().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().text())).toList();
        return new CandidateReviewEngine.Discovery(c.raw().stream().map(ReviewInput.Segment::id).toList(),
                List.of(new CandidateReviewEngine.Proposal(c.seed().anchorId(),c.seed().axis(),c.seed().reason(),quotes)),List.of(),false);
    }
    static TextReviewEngine.Result pipeline(OpenAiClient client,Condition c,ReviewGuidelineLibrary library) {
        var context=new AnalysisContext(Video.builder().filename("stage-diagnostic").build(),ContentGenre.TALK_PODCAST,
                List.of(),List.of(),new ReviewInput(c.raw()));
        return CandidateReviewEngine.run(client,context,new CandidateReviewEngine.RunServices(null,null,library,null,null),
                new CandidateReviewEngine.RunOptions(24,0,false,new CandidateReviewEngine.VerificationFeatures(false,false,false),false));
    }
    static byte[] body(byte[] bytes,String uri) {
        var bounded=V7RoutingVideoTrial.guardBody(bytes,uri);
        check(bounded.length<=MAX_BYTES,"Input byte cap exceeded");
        var tree=JSON.readTree(bounded);
        check(tree.path("max_completion_tokens").asInt()==MAX_OUTPUT,"Fixed output cap required");
        var messages=tree.path("messages");check(messages.size()==2,"Two text messages required");
        var input=JSON.readTree(messages.get(1).path("content").asText());
        check(!input.has("expectedDecision")&&!input.has("labelStatus")&&!input.has("developerProposal"),"Labels must stay outside provider input");
        return JSON.writeValueAsBytes(ordered(tree));
    }
    static final class Guard {
        int attempts,caseAttempts;double reserved;boolean blocked;byte[] expected;boolean sent;
        void beginCase(){caseAttempts=0;expected=null;sent=false;}
        void begin(byte[] bytes){check(!blocked,"Trial already blocked");expected=body(bytes,ENDPOINT);sent=false;}
        byte[] accept(byte[] bytes,String uri) {
            var guarded=body(bytes,uri);
            if(blocked||expected==null||sent||!JSON.readTree(expected).equals(JSON.readTree(guarded))) {
                blocked=true;throw new IllegalArgumentException("Unplanned HTTP or automatic retry blocked");
            }
            double next=reservation(guarded.length);
            if(attempts>=MAX_ATTEMPTS||caseAttempts>=MAX_PER_MODE||reserved+next>CAP){blocked=true;throw new IllegalArgumentException("Trial cap reached");}
            sent=true;attempts++;caseAttempts++;reserved+=next;return guarded;
        }
    }
    static final class Client extends OpenAiClient {
        final Condition condition;final String mode;final Guard guard;final boolean offline;
        final List<Frozen> captured=new ArrayList<>();final List<Map<String,Object>> calls=new ArrayList<>();
        final List<Frozen> frozen;
        Client(RestClient rest,Condition c,String mode,Guard guard,boolean offline,List<Frozen> frozen,String key) {
            super(rest,props(key),true);this.condition=c;this.mode=mode;this.guard=guard;this.offline=offline;this.frozen=frozen;
        }
        @Override public <T> Optional<T> completeAsJson(String system,String input,Class<T> type) {
            if(mode.equals("SEEDED_DIAGNOSTIC")&&type==CandidateReviewEngine.Discovery.class)return Optional.of(type.cast(seed(condition)));
            system=system(system);input=canonical(input);
            byte[] request=body(JSON.writeValueAsBytes(replayRequestBody(system,input,type)),ENDPOINT);
            var capture=new Frozen(condition.id(),mode,system,input,type.getSimpleName(),
                    ReviewGuidelineLibrary.retrievalHash(new String(request,StandardCharsets.UTF_8)));
            captured.add(capture);
            if(offline) {
                if(type==CandidateReviewEngine.Discovery.class)return Optional.of(type.cast(new CandidateReviewEngine.Discovery(
                        condition.raw().stream().map(ReviewInput.Segment::id).toList(),List.of(),List.of(),false)));
                return Optional.empty();
            }
            if(captured.size()==1)check(frozen.contains(capture),"Initial request differs from frozen preparation");
            guard.begin(request);
            Optional<T> response=super.completeAsJson(system,input,type);
            var row=new LinkedHashMap<String,Object>();row.put("kind",type.getSimpleName());row.put("system",system);
            row.put("input",JSON.readTree(input));row.put("output",response.orElse(null));row.put("failureCode",failureCode().orElse(null));
            calls.add(row);
            if(response.isEmpty()||failureCode().isPresent())guard.blocked=true;
            return response;
        }
    }
    static Plan prepare(Path sourcePath) throws Exception {
        var source=JSON.readTree(OfflineDecisionContractAudit.bounded(sourcePath));
        check(source.path("schemaVersion").asText().equals("confrontation-stage-diagnostic-plan-1"),"Known prepared source required");
        var parent=sourcePath.toRealPath().getParent();
        check(ReferenceOnlyTrial.sha(Files.readAllBytes(parent.resolve("normal-approval.json"))).equals(source.path("normalApprovalSha256").asText()),"Approval drift");
        var approval=JSON.readTree(Files.readAllBytes(parent.resolve("normal-approval.json")));
        var actualPath=Path.of("../datasets/controversy/experiments/podcast-meaning-edit-20261025/import-request-corrected-meaning.json");
        check(ReferenceOnlyTrial.sha(Files.readAllBytes(actualPath)).equals(source.path("sourceRequestSha256").asText()),"Original transcript drift");
        var actual=JSON.readTree(Files.readAllBytes(actualPath)).path("segments");
        var runtimePath=Path.of("../datasets/controversy/guidelines/runtime.json");
        String runtime=Files.readString(runtimePath);String runtimeSha=ReferenceOnlyTrial.sha(runtime.getBytes(StandardCharsets.UTF_8));
        check(runtimeSha.equals(source.path("runtimeSha256").asText()),"Runtime drift");
        var cases=new ArrayList<Condition>();
        for(var row:source.path("cases")) {
            var raw=new ArrayList<ReviewInput.Segment>();
            for(var r:row.path("raw"))raw.add(new ReviewInput.Segment(r.path("id").asText(),TimelineEventType.SPEECH,
                    r.path("startMs").asLong(),r.path("endMs").asLong(),r.path("text").asText(),null));
            var c=new Condition(row.path("id").asText(),row.path("kind").asText(),row.path("expectedDecision").asText(),List.copyOf(raw),
                    JSON.treeToValue(row.path("developerProposal"),Seed.class));validate(c);cases.add(c);
        }
        check(cases.stream().map(Condition::id).toList().equals(List.of("actual-confrontation","neutral-description","quoted-rebuttal")),"Three fixed cases required");
        check(cases.get(0).expectedDecision().equals("REVIEW_REQUIRED")&&cases.get(0).raw().size()==actual.size(),"Existing actual boundary required");
        for(int i=0;i<actual.size();i++) {
            var s=cases.get(0).raw().get(i);var original=actual.get(i);
            check(s.id().equals("stt-index-"+i)&&s.text().equals(original.path("text").asText())
                    &&s.startMs()==original.path("startMs").asLong()&&s.endMs()==original.path("endMs").asLong(),"Actual raw mismatch");
        }
        for(int i=1;i<3;i++) {
            var c=cases.get(i);var approved=approval.path("items").get(i-1);
            check(c.id().equals(approved.path("id").asText())&&c.expectedDecision().equals("PASS")
                    &&approved.path("decision").asText().equals("PASS")&&c.raw().size()==1
                    &&c.raw().get(0).text().equals(approved.path("text").asText()),"Exact approved control required");
        }
        var requests=new ArrayList<Frozen>();var library=library(runtime);
        for(var c:cases)for(String mode:List.of("AUTO","SEEDED_DIAGNOSTIC")) {
            var client=new Client(RestClient.create(),c,mode,new Guard(),true,List.of(),"offline-no-key");
            pipeline(client,c,library);check(client.captured.size()==1,"One initial request per mode required");requests.addAll(client.captured);
        }
        double worst=MAX_ATTEMPTS*reservation(MAX_BYTES);check(worst<=CAP,"Worst reservation exceeds proposed cap");
        return new Plan("confrontation-stage-executable-1","gpt-6-luna",sourcePath.toRealPath().toString(),
                ReferenceOnlyTrial.sha(Files.readAllBytes(sourcePath)),runtime,runtimeSha,implementation(),List.copyOf(cases),List.copyOf(requests),worst);
    }
    static void save(Path output,List<Map<String,Object>> rows,Guard guard,String state,String sha) throws Exception {
        Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("state",state,"planSha256",sha,"results",rows,
                "httpAttempts",guard.attempts,"reservedEstimatedUsd",guard.reserved,"estimatedCapUsd",CAP,
                "actualBillingConfirmed",false,"scope","STORED_TEXT_STAGE_DIAGNOSTIC_NOT_FULL_VIDEO_OR_INDEPENDENT_ACCURACY")));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3&&args[0].equals("prepare")||args.length==4&&args[0].equals("run"),"prepare source new-directory OR run plan approved-sha new-directory");
        var root=Path.of("../datasets/controversy/experiments").toRealPath();var output=Path.of(args[args.length-1]).toAbsolutePath().normalize();
        check(!Files.exists(output)&&output.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")) {
            var p=prepare(Path.of(args[1]));byte[] bytes=JSON.writeValueAsBytes(p);Files.createDirectory(output);
            Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
            System.out.println("STAGE_PREPARED providerCalls=0 maxHttp=24 maxPerMode=4 outputTokens=8000 inputBytes=120000 worstReservation="+p.worstReservedUsd()+" sha="+ReferenceOnlyTrial.sha(bytes));return;
        }
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));String sha=ReferenceOnlyTrial.sha(bytes);
        check(sha.equals(args[2])&&CONSENT.equals(System.getenv("CONFRONTATION_STAGE_CONSENT")),"Fresh exact-plan USD050/text consent required");
        var plan=JSON.readValue(bytes,Plan.class);var fresh=prepare(Path.of(plan.sourcePlanPath()));
        check(JSON.valueToTree(plan).equals(JSON.valueToTree(fresh)),"Code, source, cases or reference drift");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required; never print");
        DefaultVerificationTrial.claimRun(Path.of(args[1]),output,sha,CONSENT,MAX_ATTEMPTS);
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes,StandardOpenOption.CREATE_NEW);
        var guard=new Guard();var rows=new ArrayList<Map<String,Object>>();var library=library(plan.runtime());
        save(output,rows,guard,"RUNNING",sha);
        try {
            for(var c:plan.cases())for(String mode:List.of("AUTO","SEEDED_DIAGNOSTIC")) {
                if(!rows.isEmpty())Thread.sleep(15_000);
                guard.beginCase();
                var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build());factory.setReadTimeout(Duration.ofSeconds(60));
                var builder=RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).defaultHeader("Authorization","Bearer "+key)
                        .requestInterceptor((request,body,execution)->{
                            byte[] bounded=guard.accept(body,request.getURI().toString());
                            try {
                                Files.write(output.resolve("request-"+guard.attempts+".json"),bounded,StandardOpenOption.CREATE_NEW);
                                save(output,rows,guard,"RUNNING",sha);
                            }catch(Exception ex){guard.blocked=true;throw new IllegalArgumentException("Reservation persistence failed",ex);}
                            request.getHeaders().setContentLength(bounded.length);return execution.execute(request,bounded);
                        });
                String org=System.getenv("OPENAI_ORG_ID"),project=System.getenv("OPENAI_PROJECT_ID");
                if(org!=null&&!org.isBlank())builder.defaultHeader("OpenAI-Organization",org);
                if(project!=null&&!project.isBlank())builder.defaultHeader("OpenAI-Project",project);
                var rest=builder.build();
                var client=new Client(rest,c,mode,guard,false,plan.requests(),key);client.beginVideo(0L);client.beginAnalyzer("stage-diagnostic");
                long start=System.nanoTime();var result=pipeline(client,c,library);
                var row=new LinkedHashMap<String,Object>();row.put("caseId",c.id());row.put("mode",mode);row.put("expectedDecision",c.expectedDecision());
                row.put("discoveryInjected",mode.equals("SEEDED_DIAGNOSTIC"));row.put("status",result.status());row.put("diagnostics",result.diagnostics());
                row.put("evaluations",result.evaluations());row.put("unassessedSegmentIds",result.unassessedSegmentIds());row.put("calls",client.calls);
                row.put("usage",client.videoUsage());row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);
                row.put("findings",result.findings().stream().map(f -> Map.of("text",f.primaryText(),"reason",f.getReason(),"category",f.getCategory())).toList());
                rows.add(row);boolean ok=!guard.blocked&&client.failureCount()==0;
                save(output,rows,guard,ok?"RUNNING":"STOPPED",sha);client.endVideo();
                System.out.println("STAGE_RESULT "+c.id()+" "+mode+" findings="+result.findings().size()+" status="+result.status());
                check(ok,"Provider/transport/cap failure; stopped without reanalysis");
            }
            save(output,rows,guard,"COMPLETE",sha);
        }catch(Exception ex){save(output,rows,guard,"STOPPED",sha);throw ex;}
    }
}
