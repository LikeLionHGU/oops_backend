package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.*;
import org.mockito.ArgumentCaptor;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Read-only stored STT -> manually fixed candidates -> paired policy comparison, not historical replay. */
public final class ContextPolicyTrial {
    record Spec(String id,long video,int anchor,String axis,List<Integer> evidence,String labelStatus,String expected) {}
    record Task(Spec spec,String baselineSystem,String comparisonSystem,String input) {}
    static List<Spec> specs() {
        String approved="USER_REVIEWED_DEVELOPMENT_FLOW",pending="PENDING_HUMAN_REVIEW";
        return List.of(
                new Spec("64-region",64,17,"TARGET_TREATMENT",List.of(10,12,15,16,17),approved,"REVIEW_REQUIRED"),
                new Spec("64-graphic",64,48,"EXPRESSION_CONTENT",List.of(47,48),approved,"REVIEW_REQUIRED"),
                new Spec("65-product-review",65,6,"TARGET_TREATMENT",List.of(0,6,15,18),approved,"PASS"),
                new Spec("66-banter",66,145,"TARGET_TREATMENT",List.of(142,143,144,145),approved,"PASS"),
                new Spec("67-hygiene-question",67,115,"TARGET_TREATMENT",List.of(115,119,120,122,124,125,126),pending,null),
                new Spec("67-hygiene-response",67,124,"TARGET_TREATMENT",List.of(115,119,120,122,124,125,126),pending,null),
                new Spec("67-crude-wordplay",67,252,"TARGET_TREATMENT",List.of(251,252),pending,null),
                new Spec("69-naive",69,26,"TARGET_TREATMENT",List.of(24,25,26,27),pending,null),
                new Spec("69-hygiene",69,68,"TARGET_TREATMENT",List.of(68,69,70),pending,null),
                new Spec("69-reported-abuse-1",69,106,"TARGET_TREATMENT",List.of(105,106,107),pending,null),
                new Spec("69-reported-abuse-2",69,120,"TARGET_TREATMENT",List.of(119,120,121),pending,null),
                new Spec("69-political-criticism",69,136,"TARGET_TREATMENT",List.of(133,134,135,136),pending,null));
    }
    static Task capture(Spec spec,AnalysisContext context,ReviewGuidelineLibrary library) {
        var fixture=new CandidateReviewEngineTest();
        String anchor="stt-index-"+spec.anchor();
        var ids=spec.evidence().stream().map(i->"stt-index-"+i).toList();
        var roles=new SourceRelations(ids.stream().filter(id->!id.equals(anchor)).limit(1).toList(),
                List.of(),List.of(),List.of(anchor),List.of());
        var proposal=new Proposal(anchor,spec.axis(),null,ids.stream().map(id->new Quote(id,null)).toList(),roles);
        when(fixture.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(call->{
            var input=JSON.readTree((String)call.getArgument(1));List<String> primary=new ArrayList<>();
            input.get("primaryIds").forEach(n->primary.add(n.asText()));
            return Optional.of(new Discovery(primary,primary.contains(anchor)?List.of(proposal):List.of(),List.of(),false));
        });
        when(fixture.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenReturn(Optional.empty());
        run(fixture.client,context,24,null,null,library,null,0,true);
        var systems=ArgumentCaptor.forClass(String.class);var inputs=ArgumentCaptor.forClass(String.class);
        verify(fixture.client,times(1)).completeAsJson(systems.capture(),inputs.capture(),eq(VerificationResult.class));
        var pair=ContextJudgmentPolicy.pair(systems.getValue(),inputs.getValue());
        check(JSON.readTree(pair.input()).get("candidates").size()==1,"Expected exactly one manually fixed candidate");
        return new Task(spec,pair.baselineSystem(),pair.comparisonSystem(),pair.input());
    }
    static AnalysisContext context(Connection db,long videoId) throws Exception {
        var video=Video.builder().filename("private-stored-stt").build();List<TranscriptSegment> lines=new ArrayList<>();
        try(var query=db.prepareStatement("SELECT START_MS,END_MS,TEXT FROM TRANSCRIPT_SEGMENT WHERE VIDEO_ID=? ORDER BY START_MS,ID")) {
            query.setLong(1,videoId);
            try(var rows=query.executeQuery()) { while(rows.next()) lines.add(new TranscriptSegment(video,rows.getLong(1),rows.getLong(2),rows.getString(3))); }
        }
        check(!lines.isEmpty(),"Missing stored transcript");return new AnalysisContext(video,ContentGenre.GENERAL,lines,List.of());
    }
    static List<Map<String,Object>> assess(Task task,VerificationResult response) {
        var body=JSON.readTree(task.input());var c=body.get("candidates").get(0);
        var raw=CandidateHypothesisTrial.raw(body);List<Quote> quotes=new ArrayList<>();
        c.get("proposedEvidence").forEach(q->quotes.add(new Quote(q.get("segmentId").asText(),q.get("quote").asText())));
        var p=new Proposal(c.get("anchorId").asText(),c.get("axis").asText(),null,quotes,
                JSON.treeToValue(c.get("sourceRelations"),SourceRelations.class));
        Set<String> allowed=new HashSet<>();c.get("segmentIds").forEach(id->allowed.add(id.asText()));
        var candidate=new Candidate(c.get("candidateId").asText(),p,raw.segments().stream().filter(s->allowed.contains(s.id())).toList(),
                c.path("contextExpanded").asBoolean(),c.path("contextLimited").asBoolean());
        var validation=new Validation(null,"TRIAL_RESPONSE_SHAPE");
        if(response!=null && response.verifications()!=null && response.verifications().size()==1
                && response.verifications().get(0)!=null && candidate.candidateId().equals(response.verifications().get(0).candidateId()))
            validation=validate(candidate,response.verifications().get(0).assessment(),raw,0);
        Map<String,Object> row=new LinkedHashMap<>();row.put("accepted",validation.observation()!=null);
        row.put("failureCode",validation.failureCode());row.put("observation",validation.observation());return List.of(row);
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3 && Set.of("prepare","run").contains(args[0]),"prepare db-path output-dir OR run plan-path output-dir");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        if(args[0].equals("prepare")) {
            var library=ReferenceOnlyTrial.library(Path.of("../datasets/controversy/guidelines/runtime.json"));
            List<Task> tasks=new ArrayList<>();
            // IFEXISTS + read-only data mode: never create a database or start the application.
            try(var db=DriverManager.getConnection("jdbc:h2:file:"+Path.of(args[1]).toAbsolutePath()+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r","sa","")) {
                db.setReadOnly(true);
                for(var spec:specs()) tasks.add(capture(spec,context(db,spec.video()),library));
            }
            double reserve=0;for(var t:tasks) for(var system:List.of(t.baselineSystem(),t.comparisonSystem()))
                reserve+=3*((JSON.writeValueAsBytes(Map.of("system",system,"input",t.input())).length+4096)*.10/1_000_000+8000*.50/1_000_000);
            check(reserve<1,"First batch reservation exceeds $1 sub-cap");Files.createDirectory(output);
            Files.writeString(output.resolve("plan.json"),JSON.writeValueAsString(Map.of("schemaVersion","context-policy-stored-stt-1",
                    "approvedTotalEstimatedUsd",5,"subCapEstimatedUsd",1,"plannedCalls",72,"repeats",3,
                    "scope","RECONSTRUCTED_STORED_STT_MANUAL_CANDIDATES_NOT_HISTORICAL_REQUEST_REPLAY",
                    "preflightReservationEstimatedUsd",reserve,"tasks",tasks)));
            System.out.println("CONTEXT_POLICY_PREPARED tasks=12 calls=72 reserve="+reserve+" providerCalls=0");return;
        }
        Path planPath=Path.of(args[1]);check(Files.size(planPath)<=2_097_152,"Plan too large");
        byte[] bytes=Files.readAllBytes(planPath);var plan=JSON.readTree(bytes);
        check(plan.path("schemaVersion").asText().equals("context-policy-stored-stt-1") && plan.path("plannedCalls").asInt()==72,"Unknown plan");
        List<Task> tasks=new ArrayList<>();for(var item:plan.get("tasks")) tasks.add(JSON.treeToValue(item,Task.class));
        check(tasks.size()==12 && tasks.stream().map(Task::spec).toList().equals(specs()),"Task identity changed");
        for(var t:tasks) check(t.comparisonSystem().equals(ContextJudgmentPolicy.replacePolicy(t.baselineSystem()))
                && t.baselineSystem().startsWith(SourceRelationPrompts.verification(0)),"Policy pair changed");
        String key=System.getenv("OPENAI_API_KEY");check(key!=null&&!key.isBlank(),"OPENAI_API_KEY required");
        Files.createDirectory(output);Files.write(output.resolve("plan.json"),bytes);
        Files.writeString(output.resolve("plan-sha256.txt"),ReferenceOnlyTrial.sha(bytes));
        var budget=new Budget(1,.10,.50,output);
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(.10,.01,.50,null,null));
        var client=client(props,budget);List<Map<String,Object>> results=new ArrayList<>();double cost=0;
        for(int repeat=1;repeat<=3;repeat++) for(var t:tasks) for(String arm:repeat%2==1?List.of("BASELINE","COMPARISON"):List.of("COMPARISON","BASELINE")) {
            client.calls.clear();client.beginVideo(0L);client.beginAnalyzer("context-policy-trial");
            var response=client.completeAsJson(arm.equals("BASELINE")?t.baselineSystem():t.comparisonSystem(),t.input(),VerificationResult.class).orElse(null);
            var usage=client.videoUsage();cost+=usage.costUsd();
            Map<String,Object> row=new LinkedHashMap<>();row.put("caseId",t.spec().id());row.put("labelStatus",t.spec().labelStatus());
            row.put("expected",t.spec().expected());row.put("arm",arm);row.put("repeat",repeat);row.put("assessments",assess(t,response));
            row.put("calls",List.copyOf(client.calls));row.put("usage",usage);results.add(row);
            Files.writeString(output.resolve("results.json"),JSON.writeValueAsString(Map.of("results",results,"usageEstimatedUsd",cost,
                    "reservedEstimatedUsd",budget.reserved,"approvedTotalEstimatedUsd",5,"subCapEstimatedUsd",1,"actualBillingConfirmed",false)));
            System.out.println("CONTEXT_POLICY "+results.size()+"/72 "+t.spec().id()+" "+arm+" repeat="+repeat);
            int failures=client.failureCount();client.endVideo();check(!budget.exhausted&&failures==0&&usage.calls()==1,"Provider/budget/usage failure; stop");
        }
        System.out.println("CONTEXT_POLICY_COMPLETE calls="+results.size()+" estimatedUsd="+cost+" reserved="+budget.reserved);
    }
}
