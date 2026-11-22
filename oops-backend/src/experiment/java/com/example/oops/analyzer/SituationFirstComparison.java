package com.example.oops.analyzer;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Frozen original evidence, changing only the verification system's reading instructions. */
final class SituationFirstComparison {
    static final String VERSION="situation-first-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_SITUATION_FIRST_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="e65bc4a923d3737f88a0642c1a856306025ee7f5128c950972cca321cf6fcba2";
    static final String RESULTS_SHA="66c154b43ba44d383aeb25e3d08e7fbcea13a6b9de7da1eaa1dbb982ca72d8bf";
    private SituationFirstComparison() {}

    static UtteranceDirectionTrial.Plan plan(UtteranceDirectionTrial.Plan source,String sourceSha) throws Exception {
        check(UtteranceDirectionTrial.evidenceContext(source),"Frozen evidence study required");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,t.baselineSystem(),SituationFirstVerification.prompt(t.baselineSystem()),t.input())).toList();
        var plan=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,sourceSha,source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(plan);return plan;
    }

    static UtteranceDirectionTrial.Plan prepare(byte[] sourceBytes,byte[] resultBytes) throws Exception {
        check(ReferenceOnlyTrial.sha(sourceBytes).equals(SOURCE_SHA) && ReferenceOnlyTrial.sha(resultBytes).equals(RESULTS_SHA),
                "Frozen situation-first source/results required");
        check(ReferenceOnlyTrial.sha(sourceBytes).equals(JSON.readTree(resultBytes).path("planSha256").asText()),"Source/result hash mismatch");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(sourceBytes),JSON.readTree(resultBytes));
        return plan(JSON.readValue(sourceBytes,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(sourceBytes));
    }
}
