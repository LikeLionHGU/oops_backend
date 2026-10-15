package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;
import static com.example.oops.domain.TimelineEventType.SPEECH;

/** Offline boundary/provenance audit. No provider, prompt edits, manual routing or runtime mutation. */
public final class ContextRankReadinessAudit {
    static List<List<String>> canonical(ContextSemanticRankPreparation.Response response) {
        return response.groups().stream().map(g -> g.stream().sorted().toList()).toList();
    }
    static List<Map<String,Object>> pairs(ContextSemanticRankPreparation.Plan plan,String sha,JsonNode saved) {
        check("context-semantic-rank-results-1".equals(saved.path("schemaVersion").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && sha.equals(saved.path("planSha256").asText())
                && saved.path("httpAttempts").asInt()==plan.tasks().size()
                && saved.path("results").size()==plan.tasks().size(),"Complete matching rank results required");
        Map<String,ContextSemanticRankPreparation.Task> tasks=new HashMap<>();
        for(var t:plan.tasks()) check(tasks.put(t.id(),t)==null
                && t.inputSha256().equals(ReviewGuidelineLibrary.retrievalHash(t.input())),"Duplicate task or modified input");
        Map<String,List<Map<String,Object>>> groups=new TreeMap<>(); Set<String> seen=new HashSet<>();
        for(var row:saved.path("results")) {
            var task=tasks.get(row.path("id").asText());
            check(task!=null && seen.add(task.id()) && task.inputSha256().equals(row.path("inputSha256").asText())
                    && task.sourceRequestKey().equals(row.path("sourceRequestKey").asText())
                    && task.queryKind().equals(row.path("queryKind").asText()) && task.order().equals(row.path("order").asText())
                    && row.path("usage").path("calls").asInt()==1,"Rank row identity/usage mismatch");
            var response=JSON.treeToValue(row.path("response"),ContextSemanticRankPreparation.Response.class);
            check(ContextSemanticRankPreparation.failure(response,task.localIdMap().keySet())==null,"Invalid rank response");
            var item=new LinkedHashMap<String,Object>(); item.put("task",task); item.put("response",response);
            groups.computeIfAbsent(task.sourceRequestKey()+":"+task.queryKind(),k -> new ArrayList<>()).add(item);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var group:groups.values()) {
            check(group.size()==2,"One forward/reverse pair required");
            group.sort(Comparator.comparing(r -> ((ContextSemanticRankPreparation.Task)r.get("task")).order()));
            var a=(ContextSemanticRankPreparation.Task)group.get(0).get("task");
            var b=(ContextSemanticRankPreparation.Task)group.get(1).get("task");
            check(a.order().equals("FORWARD") && b.order().equals("REVERSE") && a.localIdMap().equals(b.localIdMap()),"Stable aliases required");
            var left=JSON.readTree(a.input()); var right=JSON.readTree(b.input());
            check(left.path("raw").equals(right.path("raw")),"Raw differs across order control");
            List<JsonNode> reversed=new ArrayList<>(); right.path("profiles").forEach(reversed::add); Collections.reverse(reversed);
            check(left.path("profiles").equals(JSON.valueToTree(reversed)),"Only profile order may differ");
            var x=(ContextSemanticRankPreparation.Response)group.get(0).get("response");
            var y=(ContextSemanticRankPreparation.Response)group.get(1).get("response");
            result.add(Map.of("sourceRequestKey",a.sourceRequestKey(),"queryKind",a.queryKind(),
                    "sameRanking",x.abstain().equals(y.abstain()) && canonical(x).equals(canonical(y)),
                    "forward",group.get(0),"reverse",group.get(1)));
        }
        return List.copyOf(result);
    }
    static List<Map<String,Object>> retrieval(JsonNode saved,ReviewGuidelineLibrary.Archive archive,ReviewGuidelineLibrary library) {
        check(saved.path("results").isArray() && saved.path("results").size()>0,"Stored discovery results required");
        Map<String,ReviewGuidelineLibrary.ContextExample> examples=new HashMap<>(); archive.examples().forEach(e -> examples.put(e.id(),e));
        var selector=new ContextExampleSelector(archive.examples(),archive.guidelines());
        Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        for(var row:saved.path("results")) {
            int index=0;
            for(var call:row.path("calls")) {
                int callIndex=index++;
                if(!call.path("output").has("candidates") || !call.path("input").path("raw").isArray()) continue;
                var raw=CandidateHypothesisTrial.raw(call.path("input"));
                String hash=ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(call.path("input").path("raw")));
                String caseId=row.path("caseId").asText(),key=caseId+":"+hash;
                if(unique.containsKey(key)) continue; // Identical repeated raw is not an independent case.
                var selected=library.select(SPEECH,raw.segments(),key);
                var pool=selector.rank(SPEECH,raw.segments(),Set.of());
                var missing=selected.trace().exampleIds().stream().filter(id -> examples.get(id).relationProfile()==null).toList();
                String state=selected.trace().exampleIds().size()<2?"FEWER_THAN_TWO_REFERENCES":!missing.isEmpty()?"UNPROFILED_SELECTED_REFERENCE":"READY_SELECTED_POOL_ONLY";
                var r=new LinkedHashMap<String,Object>(); r.put("caseId",caseId); r.put("rawSha256",hash);
                r.put("sourceRepeat",row.path("repeat").asInt()); r.put("sourceCallIndex",callIndex);
                r.put("selection",selected.trace()); r.put("eligibleExampleIds",pool.stream().map(m -> m.example().id()).toList());
                r.put("unprofiledSelectedIds",missing); r.put("rankReadiness",state);
                // Source IDs and text are copied, never normalized/corrected. Saved times remain local provenance only.
                r.put("savedRaw",call.path("input").path("raw"));
                r.put("comparisonInputs",List.of());
                if(state.equals("READY_SELECTED_POOL_ONLY")) {
                    List<ContextSemanticRankPreparation.Profile> profiles=new ArrayList<>(); Map<String,String> aliases=new LinkedHashMap<>();
                    for(String id:selected.trace().exampleIds().stream().sorted().toList()) {
                        String alias="r"+(profiles.size()+1); var p=examples.get(id).relationProfile(); aliases.put(alias,id);
                        profiles.add(new ContextSemanticRankPreparation.Profile(alias,p.situation(),p.target(),p.evaluation(),p.linkage()));
                    }
                    var rawText=raw.segments().stream().map(s -> Map.of("id",s.id(),"text",s.text())).toList();
                    var reverse=new ArrayList<>(profiles); Collections.reverse(reverse);
                    r.put("comparisonInputs",List.of(Map.of("raw",rawText,"profiles",profiles),Map.of("raw",rawText,"profiles",reverse)));
                    r.put("localIdMap",aliases);
                }
                unique.put(key,r);
            }
        }
        check(!unique.isEmpty(),"At least one stored discovery input required"); return List.copyOf(unique.values());
    }
    static ReviewGuidelineLibrary library(String text) {
        var lib=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(text),true,"classpath:frozen.json",6000,3,3000,"");
        lib.load(); check("READY_WORKING_REFERENCE".equals(lib.trace(SPEECH).state()),"Ready unexpired archive required"); return lib;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==6,"Rank plan/results, stored discovery results, active/preview archives, NEW private output required");
        Path output=Path.of(args[5]).toAbsolutePath().normalize(),root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private output required");
        List<byte[]> bytes=new ArrayList<>(); for(int i=0;i<5;i++) bytes.add(OfflineDecisionContractAudit.bounded(Path.of(args[i])));
        var p=JSON.readValue(bytes.get(0),ContextSemanticRankPreparation.Plan.class);
        var pairRows=pairs(p,ReferenceOnlyTrial.sha(bytes.get(0)),JSON.readTree(bytes.get(1)));
        var source=JSON.readTree(bytes.get(2)); var arms=new LinkedHashMap<String,Object>();
        for(int i=3;i<=4;i++) {
            String text=new String(bytes.get(i),java.nio.charset.StandardCharsets.UTF_8);
            arms.put(i==3?"ACTIVE_V4":"PREVIEW_V7",retrieval(source,JSON.readValue(text,ReviewGuidelineLibrary.Archive.class),library(text)));
        }
        var report=new LinkedHashMap<String,Object>(); report.put("schemaVersion","context-rank-readiness-audit-1");
        report.put("providerCalls",0); report.put("executionEnabled",false); report.put("authorizationState","NEW_RUN_REQUIRES_NEW_CONSENT");
        report.put("scope","SAVED_DEVELOPMENT_DISCOVERY_TEXT_SYNTHETIC_TIMING_NOT_NEW_VIDEO_OR_INDEPENDENT_GOLD");
        report.put("normalReferencePresenceIsNotFalsePositive",true); report.put("verdictAccuracyMeasured",false);
        report.put("manualKeysOrRolesInjected",false); report.put("rankPairs",pairRows); report.put("retrievalArms",arms);
        List<String> hashes=new ArrayList<>(); for(var b:bytes) hashes.add(ReferenceOnlyTrial.sha(b)); report.put("inputSha256InArgumentOrder",hashes);
        Files.writeString(output,JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        System.out.println("RANK_READINESS pairs="+pairRows.size()+" changed="+pairRows.stream().filter(r -> Boolean.FALSE.equals(r.get("sameRanking"))).count()+" providerCalls=0");
    }
}
