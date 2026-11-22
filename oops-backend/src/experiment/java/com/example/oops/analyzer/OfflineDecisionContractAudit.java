package com.example.oops.analyzer;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.TextBoundaryTrial.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Offline parsed-response replay. No API client, repair, label generation or production writes. */
public final class OfflineDecisionContractAudit {
    private OfflineDecisionContractAudit() {}
    static final Set<String> DECISIONS = Set.of("PASS", "REVIEW_REQUIRED", "UNCERTAIN");

    static Map<String,Object> inspect(ContextPolicyTrial.Task task, JsonNode output) {
        return replay(task, output).report();
    }

    record Replay(Map<String,Object> report, Map<String,Object> validation) {}

    private static Replay replay(ContextPolicyTrial.Task task, JsonNode output) {
        CandidateReviewEngine.VerificationResult response = null;
        String parsedDecision = null;
        boolean readable = false;
        try {
            response = JSON.treeToValue(output, CandidateReviewEngine.VerificationResult.class);
            var candidateId = JSON.readTree(task.input()).path("candidates").get(0).path("candidateId").asText();
            if (response != null && response.verifications() != null && response.verifications().size() == 1) {
                var item = response.verifications().get(0);
                if (item != null && candidateId.equals(item.candidateId()) && item.assessment() != null) {
                    parsedDecision = item.assessment().decision();
                    readable = parsedDecision != null && DECISIONS.contains(parsedDecision);
                }
            }
        } catch (RuntimeException invalidParsedShape) {
            // Saved parsed JSON only. This cannot diagnose lost original HTTP/invalid-JSON responses.
        }
        var validation = ContextPolicyTrial.assess(task, response).get(0);
        boolean accepted = Boolean.TRUE.equals(validation.get("accepted"));
        String failure = (String) validation.get("failureCode");
        boolean labeled = "USER_REVIEWED_DEVELOPMENT_FLOW".equals(task.spec().labelStatus())
                && task.spec().expected() != null && DECISIONS.contains(task.spec().expected());
        String agreement = !labeled ? "UNLABELED" : !readable ? "UNREADABLE"
                : task.spec().expected().equals(parsedDecision) ? "MATCH" : "MISMATCH";
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("caseId", task.spec().id()); row.put("labelStatus", task.spec().labelStatus());
        row.put("developmentExpected", labeled ? task.spec().expected() : null);
        row.put("parsedModelDecision", readable ? parsedDecision : null);
        row.put("decisionReadable", readable); row.put("developmentLabelAgreement", agreement);
        row.put("contractAccepted", accepted); row.put("contractFailureCode", failure);
        row.put("contractFailureKind", accepted ? "NONE" : failureKind(failure));
        row.put("evidenceMeaningReview", "NOT_AUTOMATICALLY_ASSESSED");
        return new Replay(row, validation);
    }

    static String failureKind(String code) {
        if ("LINKED_PASS_CONTRAST_REQUIRED".equals(code)) return "REQUIRED_CONTRAST_FIELD";
        if ("TARGET_MENTION_RELATION".equals(code)) return "GROUNDING_RELATION_CONTRACT";
        if ("TARGET_EVIDENCE_REQUIRED".equals(code)) return "TARGET_GROUNDING_CONTRACT";
        return "OTHER_CONTRACT_OR_RESPONSE_SHAPE";
    }

    static Map<String,Object> audit(JsonNode plan, JsonNode saved) {
        List<ContextPolicyTrial.Task> tasks = new ArrayList<>();
        plan.path("tasks").forEach(t -> tasks.add(JSON.treeToValue(t, ContextPolicyTrial.Task.class)));
        BanterPolicyTrial.validatePlan(plan, tasks);
        Map<String,ContextPolicyTrial.Task> byId = new HashMap<>();
        tasks.forEach(t -> byId.put(t.spec().id(), t));
        var results = saved.path("results");
        check(results.isArray() && results.size() == 72, "Complete 72-row study required");
        Set<String> seen = new HashSet<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var result : results) {
            String id = result.path("caseId").asText(), arm = result.path("arm").asText();
            int repeat = result.path("repeat").asInt();
            check(byId.containsKey(id) && Set.of("BASELINE", "COMPARISON").contains(arm)
                    && repeat >= 1 && repeat <= 3 && seen.add(id + ":" + arm + ":" + repeat), "Unknown/duplicate study tuple");
            var task = byId.get(id);
            var calls = result.path("calls");
            check(calls.isArray() && calls.size() == 1, "Exactly one saved parsed call required");
            var call = calls.get(0);
            String system = arm.equals("BASELINE") ? task.baselineSystem() : task.comparisonSystem();
            check(system.equals(call.path("system").asText()) && JSON.readTree(task.input()).equals(call.path("input"))
                    && call.path("failureCode").isNull(), "Frozen request mismatch/provider failure");
            var replay = replay(task, call.path("output"));
            var row = replay.report();
            var prior = result.path("assessments");
            check(prior.isArray() && prior.size() == 1
                    && prior.get(0).path("accepted").isBoolean()
                    && prior.get(0).path("accepted").asBoolean() == (boolean) row.get("contractAccepted")
                    && Objects.equals(prior.get(0).path("failureCode").isNull() ? null
                            : prior.get(0).path("failureCode").asText(), row.get("contractFailureCode"))
                    && prior.get(0).path("observation").equals(JSON.valueToTree(replay.validation().get("observation"))),
                    "Validator replay drift; do not rewrite historical outcomes");
            row.put("arm", arm); row.put("repeat", repeat); rows.add(row);
        }
        Map<String,Object> summary = new LinkedHashMap<>();
        for (String arm : List.of("BASELINE", "COMPARISON")) {
            var selected = rows.stream().filter(r -> arm.equals(r.get("arm"))).toList();
            summary.put(arm, Map.of("rows", selected.size(),
                    "parsedDecisions", counts(selected, "parsedModelDecision"),
                    "developmentLabelAgreement", counts(selected, "developmentLabelAgreement"),
                    "contractFailureKinds", counts(selected, "contractFailureKind"),
                    "contractFailureCodes", counts(selected, "contractFailureCode"),
                    "contractAccepted", selected.stream().filter(r -> Boolean.TRUE.equals(r.get("contractAccepted"))).count()));
        }
        return Map.of("schemaVersion", "offline-decision-contract-audit-1", "providerCalls", 0,
                "scope", "PARSED_RESPONSES_LABEL_AGREEMENT_AND_UNMODIFIED_VALIDATOR_REPLAY",
                "semanticCorrectnessAutomaticallyEstablished", false, "summary", summary, "rows", rows);
    }

    static Map<String,Long> counts(List<Map<String,Object>> rows, String field) {
        Map<String,Long> counts = new TreeMap<>();
        rows.forEach(r -> counts.merge(Objects.toString(r.get(field), "NONE"), 1L, Long::sum));
        return counts;
    }

    static byte[] bounded(Path path) throws Exception {
        check(Files.size(path) <= 8_388_608, "Audit input exceeds 8 MiB");
        return Files.readAllBytes(path);
    }

    /** One-field counterfactuals on copies, never repairs or semantic judgments. */
    static List<Map<String,Object>> factorialProbes(VerificationFactorialPlan.Request request,JsonNode output,String failure) {
        if (!Set.of("TARGET_MENTION_RELATION","LINKED_PASS_CONTRAST_REQUIRED").contains(Objects.toString(failure,""))) return List.of();
        var item=output.path("verifications").path(0);var assessment=item.path("assessment");
        if (!assessment.isObject()) return List.of();
        var changes=new LinkedHashMap<String,String>();
        if ("TARGET_MENTION_RELATION".equals(failure)) {
            if (assessment.path("targetMention").isString() && !assessment.path("targetMention").asText().isBlank())
                changes.put("target",assessment.path("targetMention").asText());
            changes.put("targetRelation","CONTEXTUAL");
        } else {
            changes.put("alternativeInterpretation","대조 설명 필드의 존재만 검사하는 합성 진단 문장입니다. 의미상 충분한 대조라는 승인은 아닙니다.");
        }
        var probes=new ArrayList<Map<String,Object>>();
        for (var change:changes.entrySet()) {
            JsonNode copy=output.deepCopy();
            ((tools.jackson.databind.node.ObjectNode)copy.path("verifications").get(0).path("assessment")).put(change.getKey(),change.getValue());
            var replay=VerificationFactorialPlan.assess(request,JSON.treeToValue(copy,CandidateReviewEngine.VerificationResult.class));
            var probe=new LinkedHashMap<String,Object>();probe.put("changedField",change.getKey());
            probe.put("counterfactualOnly",true);probe.put("originalDecisionPreserved",assessment.path("decision").equals(copy.at("/verifications/0/assessment/decision")));
            probe.put("contractAccepted",replay.get("accepted"));probe.put("failureCode",replay.get("failureCode"));
            probe.put("semanticCorrectnessEstablished",false);probes.add(probe);
        }
        return List.copyOf(probes);
    }

    static Map<String,Object> auditFactorial(JsonNode planNode,JsonNode saved) {
        var plan=JSON.treeToValue(planNode,VerificationFactorialPlan.Plan.class);
        check(VerificationFactorialPlan.VERSION.equals(plan.schemaVersion()) && plan.requests().size()==56
                && plan.maxRequests()==112 && plan.repeats()==2,"Known factorial plan required");
        check("verification-factorial-results-1".equals(saved.path("schemaVersion").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && saved.path("httpAttempts").asInt()==112
                && saved.path("results").size()==112,"Complete 112-row study required");
        var slots=VerificationFactorialTrial.schedule(plan);var rows=new ArrayList<Map<String,Object>>();
        for (int i=0;i<slots.size();i++) {
            var slot=slots.get(i);var request=slot.request();var result=saved.path("results").get(i);
            check(request.caseId().equals(result.path("caseId").asText()) && request.arm().name().equals(result.path("arm").asText())
                    && slot.repeat()==result.path("repeat").asInt() && result.path("calls").size()==1,"Frozen ordered study tuple required");
            var call=result.path("calls").get(0);
            check(request.system().equals(call.path("system").asText()) && JSON.readTree(request.input()).equals(call.path("input"))
                    && call.path("failureCode").isNull() && Objects.equals(request.expectedDecision(),result.path("expectedDecision").isNull()?null:result.path("expectedDecision").asText()),
                    "Frozen factorial request/label mismatch or provider failure");
            var output=call.path("output");var response=JSON.treeToValue(output,CandidateReviewEngine.VerificationResult.class);
            var replay=VerificationFactorialPlan.assess(request,response);var prior=result.path("assessment");
            check(prior.equals(JSON.valueToTree(replay)),"Factorial validator replay drift; preserve historical outcomes");
            check(result.path("boundaryEvaluation").equals(JSON.valueToTree(VerificationFactorialTrial.score(request,response,replay))),"Boundary score drift");
            var d=output.at("/verifications/0/assessment");String failure=(String)replay.get("failureCode");
            var row=new LinkedHashMap<String,Object>();row.put("caseId",request.caseId());row.put("arm",request.arm());row.put("repeat",slot.repeat());
            row.put("developmentExpected",request.expectedDecision());row.put("parsedModelDecision",d.path("decision").asText(null));
            row.put("contractAccepted",replay.get("accepted"));row.put("contractFailureCode",failure);
            row.put("contractFailureKind",failure==null?"NONE":failureKind(failure));
            row.put("evidenceMeaningReview","NOT_AUTOMATICALLY_ASSESSED");
            if ("TARGET_MENTION_RELATION".equals(failure)) {
                row.put("declaredRelation",d.path("targetRelation").asText(null));
                row.put("targetEqualsMention",d.path("target").equals(d.path("targetMention")));
                row.put("mechanicalTrigger","DIFFERENT_TARGET_AND_MENTION_REQUIRE_CONTEXTUAL");
            }
            if ("LINKED_PASS_CONTRAST_REQUIRED".equals(failure)) {
                row.put("contrastFieldPresent",!d.path("alternativeInterpretation").isNull() && !d.path("alternativeInterpretation").isMissingNode());
                row.put("reasonStillPresent",!d.path("reason").asText().isBlank());
            }
            row.put("counterfactuals",factorialProbes(request,output,failure));rows.add(row);
        }
        return Map.of("schemaVersion","offline-factorial-contract-audit-1","providerCalls",0,
                "historicalContractDrift",0,"semanticCorrectnessAutomaticallyEstablished",false,
                "contractFailureCodes",counts(rows,"contractFailureCode"),"rows",rows);
    }

    /** Literal provenance only. Substring presence does not establish a referent or speech act. */
    static Map<String,Object> targetProvenance(UtteranceDirectionTrial.Task task,JsonNode output) {
        var input=JSON.readTree(task.input());var candidate=input.path("candidates").get(0);
        var allowed=new HashSet<String>();candidate.path("segmentIds").forEach(n -> allowed.add(n.asText()));
        var proposed=new LinkedHashSet<String>();candidate.path("proposedEvidence").forEach(n -> proposed.add(n.path("segmentId").asText()));
        var raw=new LinkedHashMap<String,String>();
        input.path("raw").forEach(n -> { if(allowed.contains(n.path("id").asText())) raw.put(n.path("id").asText(),n.path("text").asText()); });
        var d=output.at("/verifications/0/assessment");
        String mention=d.path("targetMention").asText(null);
        if(mention==null) mention=d.path("target").asText(null);
        final String literal=mention;
        var matches=raw.entrySet().stream().filter(e -> literal!=null && !literal.isBlank() && e.getValue().contains(literal)).map(Map.Entry::getKey).toList();
        var selected=new ArrayList<Map<String,Object>>();var cited=new HashSet<String>();
        d.path("evidence").forEach(e -> {
            String id=e.path("segmentId").asText();cited.add(id);
            selected.add(Map.of("segmentId",id,"role",e.path("role").asText(),"allowedRawExists",raw.containsKey(id),
                    "containsLiteralMention",matches.contains(id)));
        });
        return Map.of("literalMention",Objects.toString(mention,""),"literalMatchingAllowedIds",matches,
                "selectedEvidence",selected,"proposedEvidenceIds",proposed,
                "allowedButNotProposedIds",raw.keySet().stream().filter(id -> !proposed.contains(id)).toList(),
                "allowedButNotCitedIds",raw.keySet().stream().filter(id -> !cited.contains(id)).toList(),
                "hypothesisPresent",!candidate.path("hypothesisNotEvidence").asText().isBlank(),
                "semanticCorrectnessEstablished",false);
    }

    static List<Map<String,Object>> targetIdProbes(UtteranceDirectionTrial.Task task,JsonNode output,String failure) {
        if(!"TARGET_EVIDENCE_REQUIRED".equals(failure)) return List.of();
        var provenance=targetProvenance(task,output);
        @SuppressWarnings("unchecked") var matches=(List<String>)provenance.get("literalMatchingAllowedIds");
        var evidence=output.at("/verifications/0/assessment/evidence");
        if(!evidence.isArray()) return List.of();
        var targetIndices=new ArrayList<Integer>();
        for(int i=0;i<evidence.size();i++) if("TARGET".equals(evidence.get(i).path("role").asText())) targetIndices.add(i);
        var probes=new ArrayList<Map<String,Object>>();
        for(String id:matches) {
            var indices=targetIndices.isEmpty()?List.of(-1):targetIndices;
            for(int index:indices) {
                if(index>=0 && id.equals(evidence.get(index).path("segmentId").asText())) continue;
                JsonNode copy=output.deepCopy();
                var copied=(tools.jackson.databind.node.ArrayNode)copy.at("/verifications/0/assessment/evidence");
                if(index<0) copied.addObject().put("segmentId",id).put("role","TARGET");
                else ((tools.jackson.databind.node.ObjectNode)copied.get(index)).put("segmentId",id);
                var replay=UtteranceDirectionTrial.assess(task,JSON.treeToValue(copy,CandidateReviewEngine.VerificationResult.class));
                var p=new LinkedHashMap<String,Object>();p.put("changedField","evidence");p.put("counterfactualOnly",true);
                p.put("changeKind",index<0?"APPEND_TARGET_ROLE":"REPOINT_TARGET_ID");p.put("selectedLiteralMatchId",id);
                p.put("originalDecisionPreserved",output.at("/verifications/0/assessment/decision").equals(copy.at("/verifications/0/assessment/decision")));
                p.put("contractAccepted",replay.get("accepted"));p.put("failureCode",replay.get("failureCode"));
                p.put("semanticCorrectnessEstablished",false);probes.add(p);
            }
        }
        return probes;
    }

    static Map<String,Object> auditDirectWriting(JsonNode planNode,JsonNode saved) throws Exception {
        var plan=JSON.treeToValue(planNode,UtteranceDirectionTrial.Plan.class);UtteranceDirectionTrial.validate(plan);
        boolean neutral=UtteranceDirectionTrial.neutral(plan);
        boolean evidenceContext=UtteranceDirectionTrial.evidenceContext(plan);
        int expected=UtteranceDirectionTrial.maxAttempts(plan);
        check((UtteranceDirectionTrial.direct(plan) || neutral || evidenceContext || UtteranceDirectionTrial.situationFirst(plan) || UtteranceDirectionTrial.understandingStudy(plan) || UtteranceDirectionTrial.removal(plan)) && plan.schemaVersion().equals(saved.path("schemaVersion").asText())
                && "COMPLETE".equals(saved.path("state").asText()) && saved.path("httpAttempts").asInt()==expected
                && saved.path("results").size()==expected,"Complete fixed verification study required");
        var rows=new ArrayList<Map<String,Object>>();int position=0;
        for(var slot:UtteranceDirectionTrial.schedule(plan)) {
                var task=slot.task();String arm=slot.arm();int repeat=slot.repeat();
                var result=saved.path("results").get(position++);
                check(task.id().equals(result.path("caseId").asText()) && repeat==result.path("repeat").asInt()
                        && arm.equals(result.path("arm").asText()) && result.path("calls").size()==1,"Frozen ordered direct-writing tuple required");
                var call=result.path("calls").get(0);
                String expectedInput=UtteranceDirectionTrial.requestInput(plan,task,arm.equals("COMPARISON"));
                check((arm.equals("BASELINE")?task.baselineSystem():task.comparisonSystem()).equals(call.path("system").asText())
                        && JSON.readTree(expectedInput).equals(call.path("input")) && call.path("failureCode").isNull()
                        && JSON.valueToTree(task.expectedDecision()).equals(result.path("expectedDecision"))
                        && task.labelStatus().equals(result.path("labelStatus").asText()),"Frozen direct-writing request/label mismatch");
                var actualTask=new UtteranceDirectionTrial.Task(task.id(),task.labelStatus(),task.expectedDecision(),null,null,
                        task.baselineSystem(),task.comparisonSystem(),expectedInput);
                var output=call.path("output");var replay=UtteranceDirectionTrial.assess(plan,actualTask,JSON.treeToValue(output,CandidateReviewEngine.VerificationResult.class),arm.equals("COMPARISON"));
                check(result.path("assessment").equals(JSON.valueToTree(replay)),"Direct-writing validator replay drift; preserve history");
                if(UtteranceDirectionTrial.understandingStudy(plan)) SourceUnderstandingComparison.gate(slot,replay);
                var row=new LinkedHashMap<String,Object>();row.put("caseId",task.id());row.put("arm",arm);row.put("repeat",repeat);
                row.put("developmentExpected",task.expectedDecision());row.put("parsedModelDecision",output.at("/verifications/0/assessment/decision").asText(null));
                row.put("contractAccepted",replay.get("accepted"));row.put("contractFailureCode",replay.get("failureCode"));
                if(UtteranceDirectionTrial.understandingStudy(plan) || UtteranceDirectionTrial.removal(plan)) {
                    row.put("compatibilityOnly",repeat==0);
                    row.put("sourceUnderstanding",output.at("/verifications/0/sourceUnderstanding"));
                    row.put("speechActTruthAutomaticallyEstablished",false);
                    row.put("assessmentOnlyDiagnostic",assessmentOnlyDiagnostic(actualTask,
                            JSON.treeToValue(output,CandidateReviewEngine.VerificationResult.class),replay));
                }
                row.put("provenance",targetProvenance(actualTask,output));
                row.put("counterfactuals",targetIdProbes(actualTask,output,(String)replay.get("failureCode")));rows.add(row);
            }
        return Map.of("schemaVersion",UtteranceDirectionTrial.removal(plan)?"offline-hypothesis-removal-provenance-1":UtteranceDirectionTrial.compact(plan)?"offline-source-relations-v3-provenance-1":UtteranceDirectionTrial.relations(plan)?"offline-source-relations-v2-provenance-1":UtteranceDirectionTrial.understanding(plan)?"offline-source-understanding-provenance-1":UtteranceDirectionTrial.situationFirst(plan)?"offline-situation-first-provenance-1":evidenceContext?"offline-evidence-context-provenance-1":neutral?"offline-neutral-hypothesis-provenance-1":"offline-direct-writing-provenance-1","providerCalls",0,"historicalContractDrift",0,
                "semanticCorrectnessAutomaticallyEstablished",false,"contractFailureCodes",counts(rows,"contractFailureCode"),"rows",rows);
    }

    /** Secondary inspection of the same generated assessment, not an OFF model run or runtime fallback. */
    static Map<String,Object> assessmentOnlyDiagnostic(UtteranceDirectionTrial.Task task,
            CandidateReviewEngine.VerificationResult response,Map<String,Object> fullContract) {
        var assessment=UtteranceDirectionTrial.assess(task,response);
        boolean accepted=Boolean.TRUE.equals(assessment.get("accepted"));
        boolean fullAccepted=Boolean.TRUE.equals(fullContract.get("accepted"));
        check(!fullAccepted || accepted,"Full contract cannot bypass the assessment contract");
        var result=new LinkedHashMap<String,Object>();
        result.put("scope","SAME_GENERATED_ASSESSMENT_WITHOUT_AUXILIARY_CONTRACT_CHECK");
        result.put("contractAccepted",accepted);result.put("contractFailureCode",assessment.get("failureCode"));
        result.put("auxiliaryOnlyRejection",accepted && !fullAccepted);
        result.put("bothContractsReject",!accepted && !fullAccepted);
        result.put("modelReexecuted",false);result.put("excludedFromModelPerformance",true);
        result.put("semanticTruthAutomaticallyEstablished",false);
        return result;
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Frozen plan, saved results and new private report path required");
        byte[] plan = bounded(Path.of(args[0])), saved = bounded(Path.of(args[1]));
        var planNode=JSON.readTree(plan);var savedNode=JSON.readTree(saved);
        boolean factorial=VerificationFactorialPlan.VERSION.equals(planNode.path("schemaVersion").asText());
        boolean direct=DirectTargetWritingComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || NeutralHypothesisComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || EvidenceContextComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || SituationFirstComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || SourceUnderstandingComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || SourceRelationsComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || CompactRelationsComparison.VERSION.equals(planNode.path("schemaVersion").asText())
                || HypothesisRemovalComparison.VERSION.equals(planNode.path("schemaVersion").asText());
        if (factorial || direct) check(ReferenceOnlyTrial.sha(plan).equals(savedNode.path("planSha256").asText()),"Frozen study plan hash mismatch");
        var report = new LinkedHashMap<>(direct?auditDirectWriting(planNode,savedNode):factorial?auditFactorial(planNode,savedNode):audit(planNode,savedNode));
        report.put("planSha256", ReferenceOnlyTrial.sha(plan));
        report.put("resultsSha256", ReferenceOnlyTrial.sha(saved));
        Path root = Path.of("../datasets/controversy/experiments").toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        check(!Files.exists(output) && output.getParent().toRealPath().startsWith(root), "New private report path required");
        Files.writeString(output, JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        System.out.println("OFFLINE_AUDIT rows="+((List<?>)report.get("rows")).size()+" providerCalls=0 historicalContractDrift=0");
        System.out.println(JSON.writeValueAsString(report.get(factorial||direct?"contractFailureCodes":"summary")));
    }
}
