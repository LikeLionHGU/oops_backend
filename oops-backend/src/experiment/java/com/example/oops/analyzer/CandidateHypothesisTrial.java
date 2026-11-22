package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.TimelineEventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Explicit paid opt-in: fixed-candidate verification only, no discovery/repair/media/DB. */
public final class CandidateHypothesisTrial {
    record Task(String caseId, String armId, String system, JsonNode input, String expected, int repeats) {}
    static JsonNode noHypothesis(JsonNode input) {
        var copy=input.deepCopy();
        copy.get("candidates").forEach(c->((ObjectNode)c).remove("hypothesisNotEvidence"));
        return copy;
    }
    static List<Task> tasks(JsonNode plan, Suite suite) {
        check("c-verification-ablation-draft-1".equals(plan.path("schemaVersion").asText())
                && CandidateReviewEngine.REVISION.equals(plan.path("revision").asText())
                && !plan.path("executionEnabled").asBoolean(true) && plan.path("arms").size()==3,"Invalid/offline plan required");
        var arms=plan.get("arms"); String system=arms.get(0).path("request").path("system").asText();
        check(system.startsWith(CandidateReviewEngine.verificationPrompt(0)),"Current verification system required");
        check(arms.get(0).path("id").asText().equals("WITH_HYPOTHESIS_1")
                && arms.get(1).path("id").asText().equals("WITH_HYPOTHESIS_2")
                && arms.get(2).path("id").asText().equals("WITHOUT_HYPOTHESIS"),"Unexpected arms");
        var base=noHypothesis(arms.get(0).get("request").get("input"));
        List<Task> tasks=new ArrayList<>();
        for(var arm:arms) {
            var request=arm.get("request");
            check(system.equals(request.path("system").asText()) && base.equals(noHypothesis(request.get("input"))),"More than hypothesis changed");
            check(request.get("input").get("candidates").size()==1,"Single fixed candidate required");
            tasks.add(new Task("C",arm.path("id").asText(),system,request.get("input"),"REVIEW_REQUIRED",3));
        }
        check(!arms.get(2).get("request").get("input").get("candidates").get(0).has("hypothesisNotEvidence"),"Removal arm still has hypothesis");
        for(var c:suite.cases()) if(c.expected().equals("PASS")) {
            var input=JSON.createObjectNode(); input.put("promptRevision",CandidateReviewEngine.REVISION);
            var raw=input.putArray("raw"); var evidence=JSON.createArrayNode(); var ids=JSON.createArrayNode();
            for(int i=0;i<c.speech().size();i++) {
                String id="stt-index-"+i; ids.add(id);
                raw.addObject().put("id",id).put("startMs",i*2000L).put("endMs",i*2000L+1500).put("text",c.speech().get(i));
                evidence.addObject().put("segmentId",id).put("quote",c.speech().get(i));
            }
            var candidate=input.putArray("candidates").addObject(); candidate.put("candidateId","candidate-1");
            candidate.put("anchorId","stt-index-0"); candidate.put("axis",c.id().equals("N3-memory")?"EXPRESSION_CONTENT":"TARGET_TREATMENT");
            candidate.put("contextExpanded",false); candidate.put("contextLimited",false);
            candidate.set("segmentIds",ids); candidate.set("proposedEvidence",evidence);
            // Neutral question only. No fabricated attack, expectation label, or positive C hypothesis.
            candidate.put("hypothesisNotEvidence","제공된 원문 연결에 검토 이유가 있는지 확인한다. 구체적인 문제는 아직 확정하지 않았다.");
            tasks.add(new Task(c.id(),"WITH_NEUTRAL_HYPOTHESIS",system,input,"PASS",2));
            tasks.add(new Task(c.id(),"WITHOUT_HYPOTHESIS",system,noHypothesis(input),"PASS",2));
        }
        check(tasks.size()==15,"Missing normal controls");
        return List.copyOf(tasks);
    }
    static ReviewInput raw(JsonNode input) {
        List<ReviewInput.Segment> rows=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(var r:input.get("raw")) {
            check(ids.add(r.path("id").asText()) && !r.path("text").asText().isBlank(),"Invalid raw");
            rows.add(new ReviewInput.Segment(r.get("id").asText(),TimelineEventType.SPEECH,
                    r.get("startMs").asLong(),r.get("endMs").asLong(),r.get("text").asText(),null));
        }
        return new ReviewInput(rows);
    }
    static CandidateReviewEngine.Validation validate(Task task, CandidateReviewEngine.VerificationResult response) {
        var c=task.input().get("candidates").get(0); var input=raw(task.input());
        if(response==null || response.verifications()==null || response.verifications().size()!=1
                || response.verifications().get(0)==null || !c.get("candidateId").asText().equals(response.verifications().get(0).candidateId()))
            return new CandidateReviewEngine.Validation(null,"TRIAL_RESPONSE_SHAPE");
        List<CandidateReviewEngine.Quote> quotes=new ArrayList<>();
        c.get("proposedEvidence").forEach(e->quotes.add(new CandidateReviewEngine.Quote(e.get("segmentId").asText(),e.get("quote").asText())));
        var proposal=new CandidateReviewEngine.Proposal(c.get("anchorId").asText(),c.get("axis").asText(),
                c.path("hypothesisNotEvidence").asText(""),quotes);
        var candidate=new CandidateReviewEngine.Candidate(c.get("candidateId").asText(),proposal,input.segments(),false,false);
        return CandidateReviewEngine.validate(candidate,response.verifications().get(0).assessment(),input,0);
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Plan, new private directory, new approved budget required");
        Path source=Path.of(args[0]); check(Files.size(source)<=524288,"Plan too large");
        var plan=JSON.readTree(Files.readString(source));
        var work=tasks(plan,readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json")));
        Path root=Path.of("../datasets/controversy/experiments").toAbsolutePath().normalize();
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();
        check(directory.startsWith(root) && !directory.equals(root) && !Files.exists(directory),"New private output required");
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required");
        double cap=Double.parseDouble(args[2]), inputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_INPUT_PER_1M","0.10"));
        double outputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_OUTPUT_PER_1M","0.50"));
        var budget=new Budget(cap,inputRate,outputRate,directory);
        Files.createDirectory(directory);
        Files.writeString(directory.resolve("requests.json"),JSON.writeValueAsString(work));
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(inputRate,null,outputRate,null,null));
        var client=client(props,budget); List<Map<String,Object>> results=new ArrayList<>(); double cost=0;
        // Interleave arms within repeats to reduce simple run-order drift.
        for(int repeat=1;repeat<=3;repeat++) for(var task:work) if(repeat<=task.repeats()) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("fixed-hypothesis-trial");
            var response=client.completeAsJson(task.system(),JSON.writeValueAsString(task.input()),CandidateReviewEngine.VerificationResult.class).orElse(null);
            var validation=validate(task,response); var usage=client.videoUsage(); cost+=usage.costUsd();
            Map<String,Object> row=new LinkedHashMap<>(); row.put("caseId",task.caseId()); row.put("armId",task.armId()); row.put("repeat",repeat);
            row.put("accepted",validation.observation()!=null); row.put("failureCode",validation.failureCode());
            row.put("validatedDecision",validation.observation()==null?null:validation.observation().decision());
            row.put("observation",validation.observation()); row.put("calls",List.copyOf(client.calls)); row.put("usage",usage);
            results.add(row);
            Files.writeString(directory.resolve("results.json"),JSON.writeValueAsString(Map.of("revision",CandidateReviewEngine.REVISION,
                    "scope","FIXED_VERIFICATION_ONLY_NO_REPAIR_NOT_PRODUCTION_GATE","results",results,
                    "usageEstimatedUsd",cost,"actualBillingConfirmed",false)));
            System.out.println("ABLATION "+task.caseId()+" "+task.armId()+" repeat="+repeat+" accepted="+row.get("accepted")+" decision="+row.get("validatedDecision"));
            int failures=client.failureCount(); client.endVideo();
            check(!budget.exhausted && failures==0 && usage.calls()==1,"Provider/budget/usage failure; stop trial");
        }
        System.out.println("ABLATION_COMPLETE slots="+results.size()+" usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
    }
}
