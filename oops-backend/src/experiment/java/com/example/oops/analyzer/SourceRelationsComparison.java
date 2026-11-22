package com.example.oops.analyzer;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Frozen v1/v2 output-contract comparison; no discovery or application promotion. */
final class SourceRelationsComparison {
    static final String VERSION="source-relations-v2-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_RELATION_V2_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="5dc881f59366b0457f257131d7cafe6d16bad0be868106b85b372e09c250028a";
    static final String RESULTS_SHA="a6411f2f35a92d2b21f1ba344d7141cf8c0af6d6897ddae8b5322eeebed6125b";

    static UtteranceDirectionTrial.Plan plan(UtteranceDirectionTrial.Plan source,String sha) throws Exception {
        check(UtteranceDirectionTrial.understanding(source),"Frozen v1 understanding study required");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,t.comparisonSystem(),SourceUnderstandingRelations.fromV1Prompt(t.comparisonSystem()),t.input())).toList();
        var p=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,sha,source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(p);return p;
    }
    static UtteranceDirectionTrial.Plan prepare(byte[] source,byte[] results) throws Exception {
        check(ReferenceOnlyTrial.sha(source).equals(SOURCE_SHA) && ReferenceOnlyTrial.sha(results).equals(RESULTS_SHA),"Frozen v1 source/results required");
        check(ReferenceOnlyTrial.sha(source).equals(JSON.readTree(results).path("planSha256").asText()),"Source/result hash mismatch");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(source),JSON.readTree(results));
        return plan(JSON.readValue(source,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(source));
    }
}
