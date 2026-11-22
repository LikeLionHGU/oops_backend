package com.example.oops.analyzer;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Test-only single input-field ablation, with identical systems and frozen source evidence. */
final class NeutralHypothesisComparison {
    static final String VERSION="neutral-candidate-hypothesis-20261017-1";
    static final String CONSENT="NEW_USD050_NEUTRAL_HYPOTHESIS_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="19f2058ff554b51fca8858867248be09b123d84bad034e25b414893991177d63";
    static final String RESULTS_SHA="603209093b88c85cbe12178ce28f4170b2c98feb84da9e2c0294d094ce743b26";
    static final String NEUTRAL="검토 후보의 표현·대상·앞뒤 연결.";
    private NeutralHypothesisComparison() {}

    static String input(String original) {
        var node=(tools.jackson.databind.node.ObjectNode)JSON.readTree(original);
        check(node.path("candidates").isArray() && node.path("candidates").size()==1,"One frozen candidate required");
        var candidate=(tools.jackson.databind.node.ObjectNode)node.path("candidates").get(0);
        var hypothesis=candidate.path("hypothesisNotEvidence");
        check(hypothesis.isString() && !hypothesis.asText().isBlank() && !NEUTRAL.equals(hypothesis.asText()),"Original non-neutral hypothesis required");
        candidate.put("hypothesisNotEvidence",NEUTRAL);
        return node.toString();
    }

    static UtteranceDirectionTrial.Plan prepare(byte[] sourceBytes,byte[] resultsBytes) throws Exception {
        var source=JSON.readValue(sourceBytes,UtteranceDirectionTrial.Plan.class);
        check(UtteranceDirectionTrial.direct(source),"Frozen direct-writing source required");
        // Source replay binds order, labels, input, systems and historical assessments before preparing new requests.
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(sourceBytes),JSON.readTree(resultsBytes));
        check(ReferenceOnlyTrial.sha(sourceBytes).equals(JSON.readTree(resultsBytes).path("planSha256").asText()),"Frozen source/result hash mismatch");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,t.comparisonSystem(),t.comparisonSystem(),t.input())).toList();
        var plan=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,ReferenceOnlyTrial.sha(sourceBytes),
                source.runtimeSha256(),source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(plan);return plan;
    }
}
