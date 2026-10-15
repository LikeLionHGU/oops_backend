package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Local 2x2 replay: frozen responses, only count limit and new-key reward vary. No HTTP/credentials. */
public final class ContextSelectionFactorialAudit {
    record Arm(String id,int maxCases,boolean rewardNewKeys) {}
    static final List<Arm> ARMS=List.of(new Arm("S2_NEWKEY_ON",2,true),new Arm("S2_NEWKEY_OFF",2,false),
            new Arm("S3_NEWKEY_ON",3,true),new Arm("S3_NEWKEY_OFF",3,false));
    record Row(String requestKey,String probeId,String kind,int repeat,String arm,int expectedRank,
               int selectedCount,int payloadCodePoints,int payloadDeltaFromBaseline,boolean referenceChanged,
               List<String> addedExamples,List<String> removedExamples,ReviewGuidelineLibrary.SelectionTrace selection) {}
    record Summary(String arm,String kind,int requests,int expectedSelected,int firstSelected,int selectedExamples,
                   int referenceChanges,int summedPayloadDelta,double meanPayloadCodePoints,int maxPayloadCodePoints,
                   int missingKeyRequests,int sizeOmissionRequests,int countOmissionRequests) {}
    record Analysis(List<Row> rows,List<Summary> summary) {}

    static ReviewGuidelineLibrary library(String runtime,int max) {
        var l=new ReviewGuidelineLibrary(MechanismRoutingTrial.resources(runtime),true,"classpath:frozen.json",6000,max,3000,"");
        l.load(); check(l.relationArchive(),"Ready v7 required"); return l;
    }
    static void baselineMatches(ContextRoutingProbePlan.Outcome replay,JsonNode saved) {
        check(JSON.valueToTree(replay.beforeSelection()).equals(saved.path("beforeSelection"))
                && JSON.valueToTree(replay.afterSelection()).equals(saved.path("afterSelection"))
                && replay.beforeSelectedRank()==saved.path("beforeSelectedRank").asInt(-1)
                && replay.afterSelectedRank()==saved.path("afterSelectedRank").asInt(-1)
                && saved.path("expectedKeyProposed").isBoolean() && saved.path("expectedKeyPassed").isBoolean()
                && replay.expectedKeyProposed()==saved.path("expectedKeyProposed").asBoolean()
                && replay.expectedKeyPassed()==saved.path("expectedKeyPassed").asBoolean()
                && replay.router().state().equals(saved.path("router").path("state").asText())
                && JSON.valueToTree(replay.router().routes()).equals(saved.path("router").path("routes"))
                && JSON.valueToTree(replay.link()).equals(saved.path("link")),"Saved baseline selection/route/link must reproduce exactly");
        // Replay clock/input serialization order is not a newly executed provider request.
    }
    static Analysis audit(ContextRoutingProbePlan.Plan plan,String sha,JsonNode saved) throws Exception {
        ContextRoutingProbePlan.validate(plan);
        check(ContextRoutingProbeExecution.SCHEMA.equals(saved.path("schemaVersion").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && sha.equals(saved.path("planSha256").asText())
                && saved.path("httpAttempts").asInt()==48 && plan.tasks().size()==48
                && saved.path("results").isArray() && saved.path("results").size()==48,"Complete exact-plan 48-row source required");
        var probes=JSON.readValue(plan.probesText(),ContextRetrievalContrastAudit.Plan.class);
        Map<String,ContextRetrievalContrastAudit.Probe> byProbe=new HashMap<>(); probes.probes().forEach(q -> byProbe.put(q.id(),q));
        Map<String,ContextRoutingProbePlan.Task> tasks=new HashMap<>(); plan.tasks().forEach(t -> tasks.put(t.requestKey(),t));
        Map<String,ReviewGuidelineLibrary> libraries=new HashMap<>(); ARMS.forEach(a -> libraries.put(a.id(),library(plan.runtimeText(),a.maxCases())));
        Set<String> seen=new HashSet<>(); List<Row> rows=new ArrayList<>();
        for(var r:saved.path("results")) {
            String key=r.path("requestKey").asText(); var task=tasks.get(key);
            check(task!=null && seen.add(key) && task.probeId().equals(r.path("probeId").asText())
                    && task.repeat()==r.path("repeat").asInt() && task.inputSha256().equals(r.path("inputSha256").asText())
                    && r.path("usage").path("calls").asInt()==1 && r.path("response").isObject(),"Unique frozen source row/usage/response required");
            var q=byProbe.get(task.probeId()); check(q.kind().equals(r.path("kind").asText()),"Source probe label changed");
            var response=JSON.treeToValue(r.get("response"),ContextMechanismRouter.Response.class);
            ReviewGuidelineLibrary.SelectionTrace baseline=null;
            for(var arm:ARMS) {
                var outcome=ContextRoutingProbePlan.replay(q,libraries.get(arm.id()),plan.registryText(),response,arm.rewardNewKeys());
                var trace=outcome.afterSelection();
                if(arm.id().equals("S2_NEWKEY_ON")) { baselineMatches(outcome,r.path("outcome")); baseline=trace; }
                check(baseline!=null && trace.payloadCodePoints()<=6000 && trace.exampleIds().size()<=arm.maxCases()
                        && trace.baseCodePoints()==baseline.baseCodePoints(),"Only bounded selection may change");
                var added=trace.exampleIds().stream().filter(id -> !baselineIds(r).contains(id)).toList();
                var removed=baseline.exampleIds().stream().filter(id -> !trace.exampleIds().contains(id)).toList();
                rows.add(new Row(key,q.id(),q.kind(),task.repeat(),arm.id(),outcome.afterSelectedRank(),trace.exampleIds().size(),
                        trace.payloadCodePoints(),trace.payloadCodePoints()-baseline.payloadCodePoints(),
                        !trace.exampleIds().equals(baseline.exampleIds()),added,removed,trace));
            }
        }
        check(seen.size()==tasks.size(),"Every frozen request required");
        return new Analysis(List.copyOf(rows),summarize(rows));
    }
    private static List<String> baselineIds(JsonNode source) {
        List<String> ids=new ArrayList<>(); source.path("outcome").path("afterSelection").path("exampleIds").forEach(id -> ids.add(id.asText())); return ids;
    }
    static List<Summary> summarize(List<Row> rows) {
        var groups=new TreeMap<String,List<Row>>(); rows.forEach(r -> groups.computeIfAbsent(r.arm()+":"+r.kind(),k -> new ArrayList<>()).add(r));
        return groups.values().stream().map(g -> new Summary(g.get(0).arm(),g.get(0).kind(),g.size(),
                (int)g.stream().filter(r -> r.expectedRank()>0).count(),(int)g.stream().filter(r -> r.expectedRank()==1).count(),
                g.stream().mapToInt(Row::selectedCount).sum(),(int)g.stream().filter(Row::referenceChanged).count(),
                g.stream().mapToInt(Row::payloadDeltaFromBaseline).sum(),g.stream().mapToInt(Row::payloadCodePoints).average().orElse(0),
                g.stream().mapToInt(Row::payloadCodePoints).max().orElse(0),
                (int)g.stream().filter(r -> r.selection().balance()!=null && !r.selection().balance().uncoveredKeys().isEmpty()).count(),
                (int)g.stream().filter(r -> r.selection().balance()!=null && r.selection().balance().sizeOmissions()>0).count(),
                (int)g.stream().filter(r -> r.selection().balance()!=null && r.selection().balance().countOmissions()>0).count())).toList();
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Frozen plan, complete saved results, NEW private audit file required");
        Path output=Path.of(args[2]).toAbsolutePath().normalize(); Path root=Path.of("../datasets/controversy/experiments").toRealPath();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root),"New private report required");
        byte[] plan=OfflineDecisionContractAudit.bounded(Path.of(args[0])), results=OfflineDecisionContractAudit.bounded(Path.of(args[1]));
        String sha=ReferenceOnlyTrial.sha(plan);
        var analysis=audit(JSON.readValue(plan,ContextRoutingProbePlan.Plan.class),sha,JSON.readTree(results));
        var report=new LinkedHashMap<String,Object>(); report.put("schemaVersion","context-selection-factorial-audit-1");
        report.put("providerCalls",0); report.put("verdictAccuracyMeasured",false); report.put("normalReferencePresenceIsNotFalsePositive",true);
        report.put("labelStatus","SYNTHETIC_AUTHOR_PROPOSALS_NOT_APPROVED_GOLD"); report.put("sourceRequests",48);
        report.put("replayedSelections",analysis.rows().size()); report.put("planSha256",sha); report.put("resultsSha256",ReferenceOnlyTrial.sha(results));
        report.put("fixedBudgets",Map.of("totalCodePoints",6000,"exampleCodePoints",3000)); report.put("arms",ARMS);
        report.put("baselineReproducedRows",48); report.put("payloadUnit","JSON_CODE_POINTS_NOT_API_TOKENS_OR_COST");
        report.put("summary",analysis.summary()); report.put("rows",analysis.rows());
        Files.writeString(output,JSON.writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        analysis.summary().forEach(s -> System.out.println("SELECTION_FACTORIAL "+s));
        System.out.println("SELECTION_FACTORIAL_COMPLETE sourceRows=48 selections=192 providerCalls=0");
    }
}
