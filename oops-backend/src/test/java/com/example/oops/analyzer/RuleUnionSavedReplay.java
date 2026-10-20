package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline full engine replay. Unknown requests fail closed; no key, HTTP or Spring context. */
public final class RuleUnionSavedReplay {
    record Request(String kind,JsonNode system,JsonNode input) {}
    static JsonNode systemKey(String text) {
        String marker="contextReference=";int at=text.indexOf(marker);
        if(at<0) return JSON.valueToTree(text);
        check(at==text.lastIndexOf(marker),"Ambiguous reference marker");
        int start=at+marker.length(),end=start,depth=0;boolean quoted=false,escaped=false;
        check(start<text.length() && text.charAt(start)=='{',"Structured reference must start with object");
        for(;end<text.length();end++) {
            char ch=text.charAt(end);
            if(quoted) {
                if(escaped) escaped=false;else if(ch=='\\') escaped=true;else if(ch=='"') quoted=false;
            } else if(ch=='"') quoted=true;
            else if(ch=='{' || ch=='[') depth++;
            else if(ch=='}' || ch==']') {depth--;if(depth==0) {end++;break;}}
        }
        check(depth==0 && !quoted,"Closed reference JSON required");
        var reference=JSON.readTree(text.substring(start,end));
        check(reference.isObject(),"Structured reference object required");
        // Object key order is immaterial; strings, array order and every reference value remain exact.
        return JSON.valueToTree(Map.of("prefix",text.substring(0,at),"reference",reference,"suffix",text.substring(end)));
    }
    static final class Tape {
        final Map<Request,Deque<JsonNode>> responses=new HashMap<>();
        final List<Request> unmatched=new ArrayList<>();
        int consumed,missing,available;
        Tape(JsonNode calls) {
            check(calls.isArray() && calls.size()<=80,"Bounded saved calls required");
            for(var c:calls) {
                check(Set.of("Discovery","VerificationResult").contains(c.path("kind").asText())
                        && c.path("system").isTextual() && c.path("input").isObject()
                        && c.path("output").isObject() && c.path("failureCode").isNull(),"Successful text-only saved response required");
                var key=new Request(c.path("kind").asText(),systemKey(c.path("system").asText()),c.path("input"));
                responses.computeIfAbsent(key,ignored -> new ArrayDeque<>()).add(c.path("output"));available++;
            }
        }
        <T> Optional<T> take(String system,String input,Class<T> type) {
            var key=new Request(type.getSimpleName(),systemKey(system),JSON.readTree(input));
            var queue=responses.get(key);
            if(queue==null || queue.isEmpty()) {missing++;unmatched.add(key);return Optional.empty();}
            consumed++;return Optional.of(JSON.treeToValue(queue.removeFirst(),type));
        }
        OpenAiClient client() {
            var client=mock(OpenAiClient.class);
            when(client.completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.Discovery.class)))
                    .thenAnswer(c -> take(c.getArgument(0),c.getArgument(1),CandidateReviewEngine.Discovery.class));
            when(client.completeAsJson(anyString(),anyString(),eq(CandidateReviewEngine.VerificationResult.class)))
                    .thenAnswer(c -> take(c.getArgument(0),c.getArgument(1),CandidateReviewEngine.VerificationResult.class));
            return client;
        }
    }
    static Map<String,Object> replay(RuleUnionTrialPlan.Plan plan,JsonNode row) {
        var matches=plan.conditions().stream().filter(c -> c.id().equals(row.path("caseId").asText())).toList();
        check(matches.size()==1 && Set.of("OFF","ON").contains(row.path("arm").asText()),"Known frozen case/arm required");
        var tape=new Tape(row.path("calls"));var library=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(plan.runtimeText()),
                true,"classpath:frozen.json",6000,3,3000,"",true);library.load();
        var result=CandidateReviewEngine.run(tape.client(),MechanismRoutingTrial.context(matches.get(0)),24,null,null,library,null,0,
                false,null,new CandidateReviewEngine.VerificationFeatures(false,false,false),"ON".equals(row.path("arm").asText()));
        Map<String,Object> out=new LinkedHashMap<>();out.put("caseId",row.path("caseId").asText());out.put("arm",row.path("arm").asText());
        out.put("repeat",row.path("repeat").asInt());out.put("oldStatus",row.path("status").asText());out.put("status",result.status());
        out.put("completeRequestCoverage",tape.missing==0);out.put("missingRequests",tape.missing);out.put("consumedSavedCalls",tape.consumed);
        out.put("unusedSavedCalls",tape.available-tape.consumed);out.put("oldFindingCount",row.path("findings").size());
        out.put("unmatchedRequests",tape.unmatched);
        out.put("findingCount",result.findings().size());out.put("findingTexts",result.findings().stream().map(f -> f.primaryText()).toList());
        out.put("diagnostics",result.diagnostics());out.put("evaluations",result.evaluations());out.put("unassessedSegmentIds",result.unassessedSegmentIds());
        return out;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"saved-plan saved-results NEW-private-output-directory");
        byte[] planBytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));
        byte[] resultBytes=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        String sha=ReferenceOnlyTrial.sha(planBytes);var saved=JSON.readTree(resultBytes);
        check(RuleUnionTrial.SHA.equals(sha) && sha.equals(saved.path("planSha256").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && saved.path("results").size()==16,"Completed frozen trial required");
        var plan=JSON.readValue(planBytes,RuleUnionTrialPlan.Plan.class);
        check(ReferenceOnlyTrial.sha(plan.runtimeText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(plan.sourceHashes().get("runtime")),"Frozen runtime required");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().equals(root),"New direct private experiment directory required");
        List<Map<String,Object>> rows=new ArrayList<>();
        Set<String> slots=new HashSet<>();
        for(var row:saved.path("results")) {
            check(Set.of(1,2).contains(row.path("repeat").asInt()) && slots.add(row.path("caseId").asText()+"/"+row.path("arm").asText()+"/"+row.path("repeat").asInt()),"Unique approved slot required");
            rows.add(replay(plan,row));
        }
        boolean complete=rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("completeRequestCoverage")));
        Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var hashes=new TreeMap<>(V7RoutingVideoTrial.implementation());
        String replaySource="src/test/java/com/example/oops/analyzer/RuleUnionSavedReplay.java";
        hashes.put(replaySource,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(replaySource))));
        RuleUnionTrial.write(output.resolve("replay.json"),Map.of("schemaVersion","rule-union-offline-replay-1",
                "state",complete?"COMPLETE":"INCOMPLETE_REQUEST_COVERAGE","sourcePlanSha256",sha,
                "sourceResultsSha256",ReferenceOnlyTrial.sha(resultBytes),"currentImplementationHashes",hashes,
                "results",rows,"paidCalls",0,"newModelPerformanceMeasurement",false));
        System.out.println("OFFLINE_REPLAY rows="+rows.size()+" completeRequestCoverage="+complete+" paidCalls=0");
        check(complete,"Unmatched requests: replay is incomplete, not a model performance result");
    }
}
