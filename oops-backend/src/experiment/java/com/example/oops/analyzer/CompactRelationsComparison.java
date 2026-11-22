package com.example.oops.analyzer;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Contemporary v2/v3 control with the same fourteen frozen text inputs. */
final class CompactRelationsComparison {
    static final String VERSION="source-relations-v3-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_RELATION_V3_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="0fb41610cf9cd50b3d396843158ddacacf29d1f8e02a7b1fcc95e78371d491ed";
    static final String RESULTS_SHA="6ef7d25f8e4aae6ff7afa6c221a618997f8d71c3a7fb169bb8b83b3022388ffa";
    private CompactRelationsComparison() {}

    static UtteranceDirectionTrial.Plan plan(UtteranceDirectionTrial.Plan source,String sha) throws Exception {
        check(UtteranceDirectionTrial.relations(source),"Frozen v1/v2 study required");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,t.comparisonSystem(),SourceUnderstandingCompact.fromV2Prompt(t.comparisonSystem()),t.input())).toList();
        var p=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,sha,source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(p);return p;
    }
    static UtteranceDirectionTrial.Plan prepare(byte[] source,byte[] results) throws Exception {
        check(ReferenceOnlyTrial.sha(source).equals(SOURCE_SHA) && ReferenceOnlyTrial.sha(results).equals(RESULTS_SHA),"Frozen v2 source/results required");
        check(ReferenceOnlyTrial.sha(source).equals(JSON.readTree(results).path("planSha256").asText()),"Source/result hash mismatch");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(source),JSON.readTree(results));
        return plan(JSON.readValue(source,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(source));
    }
}
