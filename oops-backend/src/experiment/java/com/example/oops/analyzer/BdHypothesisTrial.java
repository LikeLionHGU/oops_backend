package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Paid opt-in regression of original B/D groups, not a JUnit or production entry point. */
public final class BdHypothesisTrial {
    record Task(String caseId, int sourceRepeat, int sourceCallIndex, String armId, String system, JsonNode input) {}
    static List<Task> tasks(JsonNode plan, JsonNode source) {
        check("bd-hypothesis-regression-draft-1".equals(plan.path("schemaVersion").asText())
                && CandidateReviewEngine.REVISION.equals(plan.path("revision").asText())
                && !plan.path("executionEnabled").asBoolean(true) && plan.path("pairs").size()==6,"Invalid offline plan");
        List<Task> work=new ArrayList<>(); Set<String> unique=new HashSet<>();
        for(var pair:plan.get("pairs")) {
            String id=pair.path("caseId").asText(); int repeat=pair.path("sourceRepeat").asInt(), index=pair.path("sourceCallIndex").asInt(-1);
            check(Set.of("P-B-development-excerpt","P-D-development-excerpt").contains(id)
                    && repeat>=1 && repeat<=2 && index>=0 && unique.add(id+"/"+repeat+"/"+index),"Invalid pair identity");
            List<JsonNode> rows=new ArrayList<>(); source.get("results").forEach(r->{
                if(id.equals(r.path("caseId").asText()) && repeat==r.path("repeat").asInt()) rows.add(r);
            });
            check(rows.size()==1,"Ambiguous source row");
            var call=rows.get(0).path("calls").get(index); check(call!=null && call.path("output").has("verifications"),"Missing saved verification");
            String system=call.path("system").asText(); check(system.startsWith(CandidateReviewEngine.verificationPrompt(0)),"Changed verification system");
            var original=call.get("input"); int count=original.get("candidates").size();
            check(count==(id.startsWith("P-D")?2:1) && pair.path("candidateCount").asInt()==count,"Candidate group changed");
            check(pair.path("arms").size()==2,"Two arms required");
            for(int arm=0;arm<2;arm++) {
                var entry=pair.get("arms").get(arm); String name=arm==0?"WITH_HYPOTHESIS":"WITHOUT_HYPOTHESIS";
                check(name.equals(entry.path("id").asText()) && entry.get("input").equals(
                        arm==0?original:CandidateHypothesisTrial.noHypothesis(original)),"Unexpected input changes");
                work.add(new Task(id,repeat,index,name,system,entry.get("input")));
            }
        }
        check(work.size()==12,"Incomplete request pairs"); return List.copyOf(work);
    }
    static List<Map<String,Object>> validate(Task task, CandidateReviewEngine.VerificationResult response) {
        var input=CandidateHypothesisTrial.raw(task.input()); var candidates=task.input().get("candidates");
        Set<String> expected=new HashSet<>(); candidates.forEach(c->expected.add(c.get("candidateId").asText()));
        boolean shape=response!=null && response.verifications()!=null && response.verifications().size()==expected.size();
        Set<String> returned=new HashSet<>();
        if(shape) for(var v:response.verifications()) if(v==null || !expected.contains(v.candidateId()) || !returned.add(v.candidateId())) shape=false;
        List<Map<String,Object>> validations=new ArrayList<>();
        for(var c:candidates) {
            CandidateReviewEngine.Validation result=new CandidateReviewEngine.Validation(null,"TRIAL_RESPONSE_SHAPE");
            if(shape) {
                var v=response.verifications().stream().filter(a->a.candidateId().equals(c.get("candidateId").asText())).findFirst().orElseThrow();
                List<CandidateReviewEngine.Quote> quotes=new ArrayList<>();
                c.get("proposedEvidence").forEach(e->quotes.add(new CandidateReviewEngine.Quote(e.get("segmentId").asText(),e.get("quote").asText())));
                var proposal=new CandidateReviewEngine.Proposal(c.get("anchorId").asText(),c.get("axis").asText(),c.path("hypothesisNotEvidence").asText(""),quotes);
                Set<String> allowed=new HashSet<>(); c.get("segmentIds").forEach(s->allowed.add(s.asText()));
                var candidate=new CandidateReviewEngine.Candidate(c.get("candidateId").asText(),proposal,
                        input.segments().stream().filter(s->allowed.contains(s.id())).toList(),c.path("contextExpanded").asBoolean(),c.path("contextLimited").asBoolean());
                result=CandidateReviewEngine.validate(candidate,v.assessment(),input,0);
            }
            Map<String,Object> row=new LinkedHashMap<>(); row.put("candidateId",c.get("candidateId").asText()); row.put("axis",c.get("axis").asText());
            row.put("accepted",result.observation()!=null); row.put("failureCode",result.failureCode()); row.put("observation",result.observation());
            validations.add(row);
        }
        return validations;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Plan, new output, approved budget required");
        Path root=Path.of("../datasets/controversy/experiments").toAbsolutePath().normalize();
        Path planPath=Path.of(args[0]); check(Files.size(planPath)<=1048576,"Plan too large");
        var plan=JSON.readTree(Files.readString(planPath)); Path source=root.resolve(plan.path("source").asText()).normalize();
        check(source.startsWith(root) && Files.size(source)<=4194304,"Invalid saved source");
        byte[] original=Files.readAllBytes(source);
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        check(sha.equals(plan.path("sourceSha256").asText()),"Source hash changed");
        var work=tasks(plan,JSON.readTree(original));
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();
        check(directory.startsWith(root) && !directory.equals(root) && !Files.exists(directory),"New private output required");
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required");
        double inputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_INPUT_PER_1M","0.10")),
                outputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_OUTPUT_PER_1M","0.50"));
        var budget=new Budget(Double.parseDouble(args[2]),inputRate,outputRate,directory); Files.createDirectory(directory);
        Files.writeString(directory.resolve("requests.json"),JSON.writeValueAsString(work));
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(inputRate,null,outputRate,null,null));
        var client=client(props,budget); List<Map<String,Object>> results=new ArrayList<>(); double cost=0;
        for(int repeat=1;repeat<=3;repeat++) for(var task:work) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("bd-hypothesis-trial");
            var response=client.completeAsJson(task.system(),JSON.writeValueAsString(task.input()),CandidateReviewEngine.VerificationResult.class).orElse(null);
            var assessments=validate(task,response); var usage=client.videoUsage(); cost+=usage.costUsd();
            Map<String,Object> row=new LinkedHashMap<>(); row.put("caseId",task.caseId()); row.put("sourceRepeat",task.sourceRepeat());
            row.put("sourceCallIndex",task.sourceCallIndex()); row.put("armId",task.armId()); row.put("repeat",repeat);
            row.put("assessments",assessments); row.put("calls",List.copyOf(client.calls)); row.put("usage",usage); results.add(row);
            Files.writeString(directory.resolve("results.json"),JSON.writeValueAsString(Map.of("revision",CandidateReviewEngine.REVISION,
                    "scope","FIXED_BD_GROUPS_NO_DISCOVERY_REPAIR_MEDIA_FUSION","results",results,"usageEstimatedUsd",cost,"actualBillingConfirmed",false)));
            System.out.println("BD_TRIAL "+task.caseId()+" source="+task.sourceRepeat()+"/"+task.sourceCallIndex()+" "+task.armId()+" repeat="+repeat);
            int failures=client.failureCount(); client.endVideo();
            check(!budget.exhausted && failures==0 && usage.calls()==1,"Provider/budget/usage failure; stop trial");
        }
        System.out.println("BD_COMPLETE calls="+results.size()+" usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
    }
}
