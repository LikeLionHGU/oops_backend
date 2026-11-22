package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Both arms use situation-first instructions. Only the observable act contract is toggled. */
final class SourceUnderstandingComparison {
    static final String VERSION="source-understanding-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_SOURCE_UNDERSTANDING_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="a47f5b098dbfbc7601b4f11a7b6a622eb4b1ef9abe0a651846dc5fc10a35552e";
    static final String RESULTS_SHA="c60c2a59abc08a3cf15afb5d15001dfac9f8cb91ea01871bd8020e40d459e02b";
    static final String SMOKE_ID="source-understanding-compatibility";
    private SourceUnderstandingComparison() {}

    static UtteranceDirectionTrial.Plan plan(UtteranceDirectionTrial.Plan source,String sha) throws Exception {
        check(UtteranceDirectionTrial.situationFirst(source),"Frozen situation-first study required");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,t.comparisonSystem(),SourceUnderstandingContract.fromOrderedPrompt(t.comparisonSystem()),t.input())).toList();
        var plan=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,sha,source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(plan);return plan;
    }
    static UtteranceDirectionTrial.Plan prepare(byte[] source,byte[] results) throws Exception {
        check(ReferenceOnlyTrial.sha(source).equals(SOURCE_SHA) && ReferenceOnlyTrial.sha(results).equals(RESULTS_SHA),"Frozen source-understanding source/results required");
        check(ReferenceOnlyTrial.sha(source).equals(JSON.readTree(results).path("planSha256").asText()),"Source/result hash mismatch");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(source),JSON.readTree(results));
        return plan(JSON.readValue(source,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(source));
    }
    static UtteranceDirectionTrial.Task smoke(UtteranceDirectionTrial.Plan plan) {
        var input=JSON.createObjectNode();input.put("promptRevision",SourceUnderstandingContract.VERSION);
        input.putArray("raw").addObject().put("id","smoke-0").put("text","지금은 단어의 뜻을 설명하고 있습니다.")
                .put("startMs",0).put("endMs",1500).put("type","SPEECH");
        var c=input.putArray("candidates").addObject();c.put("candidateId","compatibility-1");c.put("anchorId","smoke-0");
        c.put("axis","TARGET_TREATMENT");c.put("hypothesisNotEvidence","원문 행위를 확인합니다.");
        c.putArray("segmentIds").add("smoke-0");c.put("contextExpanded",false);c.put("contextLimited",false);
        c.putArray("proposedEvidence").addObject().put("segmentId","smoke-0").put("quote","지금은 단어의 뜻을 설명하고 있습니다.");
        var first=plan.tasks().get(0);
        return new UtteranceDirectionTrial.Task(SMOKE_ID,"SYNTHETIC_COMPATIBILITY_NOT_GOLD",null,null,null,
                first.baselineSystem(),first.comparisonSystem(),input.toString());
    }
    static void gate(UtteranceDirectionTrial.Slot slot,Map<String,Object> assessment) {
        if(slot.repeat()==0) check(SMOKE_ID.equals(slot.task().id()) && Boolean.TRUE.equals(assessment.get("accepted")),
                "Compatibility failed; stop before main comparison without retry");
    }
}
