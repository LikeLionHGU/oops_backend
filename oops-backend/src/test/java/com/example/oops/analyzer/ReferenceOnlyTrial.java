package com.example.oops.analyzer;

import com.example.oops.config.OpenAiProperties;
import com.example.oops.domain.TimelineEventType;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.*;

/** Fixed requests: only selected historical reference changes. Prepare is entirely offline. */
public final class ReferenceOnlyTrial {
    record Task(String caseId, int sourceRepeat, int sourceCallIndex, String armId, String system,
                JsonNode input, int repeats, ReviewGuidelineLibrary.SelectionTrace referenceTrace) {}
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static ReviewGuidelineLibrary library(Path path) {
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,path.toAbsolutePath().toUri().toString(),6000);
        library.load(); check("READY_WORKING_REFERENCE".equals(library.trace(TimelineEventType.SPEECH).state()),"Reference unavailable");
        return library;
    }
    static JsonNode reference(String system) {
        String marker="contextReference="; int index=system.indexOf(marker);
        check(index>=0 && index==system.lastIndexOf(marker),"Unique reference payload required");
        return JSON.readTree(system.substring(index+marker.length()));
    }
    // Map.of iteration can vary between JVMs. Keep original property order so the
    // experimental delta is reference CONTENT, not incidental JSON reserialization.
    static JsonNode orderedLike(JsonNode value, JsonNode template) {
        if(value.isObject()) {
            var result=JSON.createObjectNode();
            if(template!=null && template.isObject()) template.properties().forEach(e->{
                if(value.has(e.getKey())) result.set(e.getKey(),orderedLike(value.get(e.getKey()),e.getValue()));
            });
            value.properties().forEach(e->{if(!result.has(e.getKey())) result.set(e.getKey(),e.getValue());}); return result;
        }
        if(value.isArray()) {
            var result=JSON.createArrayNode();
            for(int i=0;i<value.size();i++) result.add(orderedLike(value.get(i),template!=null&&template.isArray()&&i<template.size()?template.get(i):null));
            return result;
        }
        return value;
    }
    static List<Task> pair(String id, int repeat, int index, JsonNode input, ReviewInput selectionRaw,
                           ReviewGuidelineLibrary before, ReviewGuidelineLibrary after, int repeats,
                           String originalSystem) {
        List<Task> pair=new ArrayList<>();
        for(int arm=0;arm<2;arm++) {
            var selection=(arm==0?before:after).select(TimelineEventType.SPEECH,selectionRaw.segments(),"fixed-reference-trial");
            String system=CandidateReviewEngine.verificationPrompt(0)+selection.prompt();
            if(arm==0 && originalSystem!=null) {
                check(system.substring(0,system.indexOf("contextReference=")).equals(originalSystem.substring(0,originalSystem.indexOf("contextReference=")))
                        && reference(system).equals(reference(originalSystem)),"Baseline does not reproduce saved frozen reference");
                system=originalSystem;
            }
            if(arm==1) {
                String original=pair.get(0).system();
                system=original.substring(0,original.indexOf("contextReference="))+"contextReference="
                        +JSON.writeValueAsString(orderedLike(reference(system),reference(original)));
            }
            pair.add(new Task(id,repeat,index,arm==0?"BEFORE_REFERENCE":"AFTER_REFERENCE",system,input.deepCopy(),repeats,selection.trace()));
        }
        check(pair.get(0).input().equals(pair.get(1).input()),"Input changed across reference arms");
        return pair;
    }
    static List<Task> tasks(JsonNode source, Suite suite, ReviewGuidelineLibrary before, ReviewGuidelineLibrary after) {
        List<Task> work=new ArrayList<>(); int groups=0;
        for(var row:source.get("results")) if(row.path("caseId").asText().startsWith("P-")) {
            String id=row.get("caseId").asText(); int repeat=row.get("repeat").asInt();
            check(Set.of("P-B-development-excerpt","P-C-development-excerpt","P-D-development-excerpt").contains(id),"Unexpected source case");
            List<JsonNode> discoveries=new ArrayList<>();
            for(int i=0;i<row.get("calls").size();i++) {
                var call=row.get("calls").get(i);
                if(call.path("output").has("candidates")) discoveries.add(call);
                if(!call.path("output").has("verifications")) continue;
                var input=call.get("input"); Set<String> keys=new HashSet<>();
                for(var c:input.get("candidates")) {
                    List<JsonNode> traces=new ArrayList<>();
                    row.path("diagnostics").path("candidatePipeline").path("candidates").forEach(t->{
                        if(t.path("candidateId").asText().equals(c.path("candidateId").asText())) traces.add(t);
                    });
                    check(traces.size()==1,"Missing frozen candidate provenance");
                    keys.add(traces.get(0).path("inputSnapshot").path("discoveryRequestKey").asText());
                }
                check(keys.size()==1,"Mixed discovery references in source group");
                String key=keys.iterator().next(); check(key.matches("discovery-[12]"),"Invalid discovery key");
                int discoveryIndex=Integer.parseInt(key.substring("discovery-".length()))-1;
                check(discoveryIndex<discoveries.size(),"Missing original discovery raw");
                check(input.get("candidates").size()==(id.startsWith("P-D")?2:1),"Original candidate group changed");
                work.addAll(pair(id,repeat,i,input,CandidateHypothesisTrial.raw(discoveries.get(discoveryIndex).get("input")),
                        before,after,3,call.get("system").asText())); groups++;
            }
        }
        check(groups==8,"Expected four B, two C and two D request groups");
        for(var item:suite.cases()) if(item.expected().equals("PASS")) {
            var input=JSON.createObjectNode(); input.put("promptRevision",CandidateReviewEngine.REVISION);
            var raw=input.putArray("raw"); var ids=JSON.createArrayNode(); var quotes=JSON.createArrayNode();
            for(int i=0;i<item.speech().size();i++) {
                String id="stt-index-"+i; ids.add(id);
                raw.addObject().put("id",id).put("startMs",i*2000L).put("endMs",i*2000L+1500).put("text",item.speech().get(i));
                quotes.addObject().put("segmentId",id).put("quote",item.speech().get(i));
            }
            var c=input.putArray("candidates").addObject(); c.put("candidateId","candidate-1"); c.put("anchorId","stt-index-0");
            c.put("axis",item.id().equals("N3-memory")?"EXPRESSION_CONTENT":"TARGET_TREATMENT");
            c.put("contextExpanded",false); c.put("contextLimited",false); c.set("segmentIds",ids); c.set("proposedEvidence",quotes);
            c.put("hypothesisNotEvidence","제공된 원문 연결에 검토 이유가 있는지 확인한다. 구체적인 문제는 아직 확정하지 않았다.");
            work.addAll(pair(item.id(),0,-1,input,CandidateHypothesisTrial.raw(input),before,after,2,null));
        }
        check(work.size()==28 && work.stream().mapToInt(Task::repeats).sum()==72,"Expected 72 requests");
        return List.copyOf(work);
    }
    static void validatePairs(List<Task> work) {
        check(work.size()==28,"Unexpected task count");
        for(int i=0;i<work.size();i+=2) {
            var a=work.get(i); var b=work.get(i+1);
            check(a.armId().equals("BEFORE_REFERENCE") && b.armId().equals("AFTER_REFERENCE")
                    && a.caseId().equals(b.caseId()) && a.sourceRepeat()==b.sourceRepeat() && a.sourceCallIndex()==b.sourceCallIndex()
                    && a.repeats()==b.repeats() && a.input().equals(b.input()),"Unpaired or changed fixed input");
            String prefix=CandidateReviewEngine.verificationPrompt(0);
            check(a.system().startsWith(prefix) && b.system().startsWith(prefix)
                    && a.system().substring(prefix.length()).startsWith("\n")
                    && b.system().substring(prefix.length()).startsWith("\n"),"Verification instruction changed");
            check(a.system().substring(0,a.system().indexOf("contextReference=")).equals(b.system().substring(0,b.system().indexOf("contextReference=")))
                    && reference(a.system()).get("reviewGuidelines").equals(reference(b.system()).get("reviewGuidelines")),"Common contract/criteria changed");
        }
        check(work.stream().mapToInt(Task::repeats).sum()==72,"Unexpected repeat count");
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of("../datasets/controversy/experiments").toAbsolutePath().normalize();
        check(args.length>=1,"prepare or run required");
        if(args[0].equals("prepare")) {
            check(args.length==5,"prepare baseline, current, source and new plan directory");
            Path baseline=Path.of(args[1]),current=Path.of(args[2]),source=Path.of(args[3]);
            check(Files.size(source)<=4194304,"Saved source too large"); byte[] bytes=Files.readAllBytes(source);
            check(sha(bytes).equals("ef1df17c20da9ea6fdcbf7558277d25e9c50cfffb1d145e1b83ec92074b61ba3"),"Saved source changed");
            var work=tasks(JSON.readTree(bytes),readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json")),library(baseline),library(current));
            validatePairs(work); Path directory=Path.of(args[4]).toAbsolutePath().normalize();
            check(directory.startsWith(root) && !directory.equals(root) && !Files.exists(directory),"New private plan directory required");
            Files.createDirectory(directory);
            Files.writeString(directory.resolve("plan.json"),JSON.writeValueAsString(Map.of("schemaVersion","reference-only-trial-1",
                    "executionEnabled",false,"revision",CandidateReviewEngine.REVISION,"baselineSha256",sha(Files.readAllBytes(baseline)),
                    "currentSha256",sha(Files.readAllBytes(current)),"sourceSha256",sha(bytes),"tasks",work,"plannedCalls",72)));
            System.out.println("REFERENCE_PREPARED calls=72 providerCalls=0"); return;
        }
        check(args[0].equals("run") && args.length==4,"run plan, new output, newly approved budget");
        Path planPath=Path.of(args[1]); check(Files.size(planPath)<=1048576,"Plan too large"); byte[] bytes=Files.readAllBytes(planPath);
        var plan=JSON.readTree(bytes); check(plan.path("schemaVersion").asText().equals("reference-only-trial-1")
                && !plan.path("executionEnabled").asBoolean(true) && plan.path("revision").asText().equals(CandidateReviewEngine.REVISION),"Invalid plan");
        List<Task> work=new ArrayList<>(); for(var t:plan.get("tasks")) work.add(JSON.treeToValue(t,Task.class)); validatePairs(work);
        Path directory=Path.of(args[2]).toAbsolutePath().normalize();
        check(directory.startsWith(root) && !directory.equals(root) && !Files.exists(directory),"New private result directory required");
        String key=System.getenv("OPENAI_API_KEY"); check(key!=null && !key.isBlank(),"OPENAI_API_KEY required");
        double inputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_INPUT_PER_1M","0.10")),
                outputRate=Double.parseDouble(System.getenv().getOrDefault("OPENAI_OUTPUT_PER_1M","0.50"));
        var budget=new Budget(Double.parseDouble(args[3]),inputRate,outputRate,directory); Files.createDirectory(directory);
        Files.writeString(directory.resolve("requests.json"),JSON.writeValueAsString(work));
        Files.writeString(directory.resolve("plan-sha256.txt"),sha(bytes));
        var props=new OpenAiProperties(key,System.getenv("OPENAI_ORG_ID"),System.getenv("OPENAI_PROJECT_ID"),
                "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(90),new OpenAiProperties.Pricing(inputRate,null,outputRate,null,null));
        var client=client(props,budget); List<Map<String,Object>> results=new ArrayList<>(); double cost=0;
        for(int repeat=1;repeat<=3;repeat++) for(var task:work) if(repeat<=task.repeats()) {
            client.calls.clear(); client.beginVideo(0L); client.beginAnalyzer("reference-only-trial");
            var response=client.completeAsJson(task.system(),JSON.writeValueAsString(task.input()),CandidateReviewEngine.VerificationResult.class).orElse(null);
            var wrapper=new BdHypothesisTrial.Task(task.caseId(),task.sourceRepeat(),task.sourceCallIndex(),task.armId(),task.system(),task.input());
            var assessments=BdHypothesisTrial.validate(wrapper,response); var usage=client.videoUsage(); cost+=usage.costUsd();
            Map<String,Object> row=new LinkedHashMap<>(); row.put("caseId",task.caseId()); row.put("sourceRepeat",task.sourceRepeat());
            row.put("sourceCallIndex",task.sourceCallIndex()); row.put("armId",task.armId()); row.put("repeat",repeat);
            row.put("assessments",assessments); row.put("calls",List.copyOf(client.calls)); row.put("usage",usage); results.add(row);
            Files.writeString(directory.resolve("results.json"),JSON.writeValueAsString(Map.of("revision",CandidateReviewEngine.REVISION,
                    "scope","FIXED_REFERENCE_ONLY_NO_DISCOVERY_REPAIR_MEDIA_FUSION","results",results,"usageEstimatedUsd",cost,"actualBillingConfirmed",false)));
            System.out.println("REFERENCE_TRIAL "+results.size()+"/72 "+task.caseId()+" "+task.armId()+" repeat="+repeat);
            int failures=client.failureCount(); client.endVideo();
            check(!budget.exhausted && failures==0 && usage.calls()==1,"Provider/budget/usage failure; stop trial");
        }
        System.out.println("REFERENCE_COMPLETE calls="+results.size()+" usageEstimatedUsd="+cost+" reservedEstimatedUsd="+budget.reserved);
    }
}
