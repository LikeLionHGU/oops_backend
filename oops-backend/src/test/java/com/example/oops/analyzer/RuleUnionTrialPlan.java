package com.example.oops.analyzer;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Preparation only: no credentials, provider client, network, or runtime activation. */
public final class RuleUnionTrialPlan {
    record Plan(String schemaVersion, String state, String model, int repeats, int engineExecutions,
                double proposedEstimatedCapUsd, int proposedMaxHttpAttempts,
                Map<String,Object> fixedSettings, Map<String,String> sourceHashes,
                Map<String,String> implementationHashes, String runtimeText,
                List<MechanismRoutingTrial.Condition> conditions, List<String> measurements) {}

    static Plan prepare(byte[] transcript, byte[] normal, byte[] runtime) throws Exception {
        var response=JSON.readTree(transcript);
        check(response.path("success").asBoolean() && response.path("data").isArray(), "Stored transcript required");
        List<ReviewInput.Segment> raw=new ArrayList<>(); int index=0;
        for(var row:response.path("data")) {
            check(row.path("startMs").isIntegralNumber() && row.path("endMs").isIntegralNumber()
                    && row.path("text").isTextual(), "Exact stored speech required");
            raw.add(new ReviewInput.Segment("stt-index-"+index++,SPEECH,row.path("startMs").asLong(),
                    row.path("endMs").asLong(),row.path("text").asText(),null));
        }
        List<MechanismRoutingTrial.Condition> matches=new ArrayList<>();
        for(var row:JSON.readTree(normal).path("conditions")) if("v2".equals(row.path("id").asText()))
            matches.add(JSON.treeToValue(row,MechanismRoutingTrial.Condition.class));
        check(matches.size()==1 && "USER_NORMAL_PRODUCT_REVIEW_DEVELOPMENT".equals(matches.get(0).labelStatus()),
                "One saved user-normal product transcript required");
        var conditions=List.of(
                new MechanismRoutingTrial.Condition("v1-current71","KNOWN_INCIDENT_IN_DICTIONARY_NOT_HOLDOUT",List.copyOf(raw)),
                new MechanismRoutingTrial.Condition("product65",matches.get(0).labelStatus(),matches.get(0).raw()),
                draft("single-menu","선택 메뉴가 하나라 메뉴판은 의미가 없어요",
                        "오늘은 주는대로 먹어야 돼요. 미리 안내받고 동의했어요"),
                draft("safety-education","피부를 찢는 사고를 예방하는 안전 교육입니다"));
        for(var c:conditions) {
            check(!c.raw().isEmpty() && c.raw().size()<=80 && c.raw().stream().allMatch(s ->
                    s.type()==SPEECH && s.text()!=null && !s.text().isBlank() && s.text().length()<=24000
                    && s.startMs()>=0 && s.endMs()>=s.startMs()),"Bounded speech only");
            new ReviewInput(c.raw());
        }
        var hashes=new TreeMap<>(V7RoutingVideoTrial.implementation());
        for(String path:List.of("src/test/java/com/example/oops/analyzer/RuleUnionTrialPlan.java",
                "src/main/resources/context-search-profiles.json","src/main/resources/candidate-discovery-output.schema.json"))
            hashes.put(path,ReferenceOnlyTrial.sha(Files.readAllBytes(Path.of(path))));
        return new Plan("rule-union-trial-plan-1","PREPARED_NOT_APPROVED_NOT_EXECUTED","gpt-6-luna",2,16,.50,80,
                Map.of("comparisonFactor","RULE_CANDIDATE_UNION_ONLY","structuredSearch",true,"strictDiscovery",true,
                        "scopedIds",true,"otherExperimentalFlags",false,"visual",false,"maxCandidates",24,
                        "order",List.of(List.of("OFF","ON"),List.of("ON","OFF"))),
                Map.of("transcript",ReferenceOnlyTrial.sha(transcript),"normalSource",ReferenceOnlyTrial.sha(normal),
                        "runtime",ReferenceOnlyTrial.sha(runtime)),Map.copyOf(hashes),new String(runtime,StandardCharsets.UTF_8),
                conditions,List.of("candidate omissions and rule additions","verification rejection reasons",
                        "normal warnings (drafts are not human gold)","conflicts and partial coverage",
                        "duplicate issues","repeat variability","latency and usage cost"));
    }
    static MechanismRoutingTrial.Condition draft(String id,String... text) {
        List<ReviewInput.Segment> raw=new ArrayList<>();
        for(int i=0;i<text.length;i++) raw.add(new ReviewInput.Segment("draft-"+id+"-"+i,SPEECH,i*4000,i*4000+3500,text[i],null));
        return new MechanismRoutingTrial.Condition(id,"SYNTHETIC_DRAFT_NOT_HUMAN_GOLD_NOT_PROMOTED",List.copyOf(raw));
    }
    public static void main(String[] args) throws Exception {
        check(args.length==4,"transcript normal-source runtime NEW-output-directory");
        Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        Path output=Path.of(args[3]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().equals(root),"New direct private experiment directory required");
        var plan=prepare(OfflineDecisionContractAudit.bounded(Path.of(args[0])),
                OfflineDecisionContractAudit.bounded(Path.of(args[1])),OfflineDecisionContractAudit.bounded(Path.of(args[2])));
        byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan);
        Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createFile(output.resolve("plan.json"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(output.resolve("plan.json"),bytes);
        System.out.println(JSON.writeValueAsString(Map.of("state",plan.state(),"engineExecutions",16,
                "planSha256",ReferenceOnlyTrial.sha(bytes),"proposedEstimatedCapUsd",.50,"paidCalls",0)));
    }
}
