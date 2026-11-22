package com.example.oops.analyzer;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Input-field deletion only. Both arms use the same v3 contract and current permitted-context rule. */
final class HypothesisRemovalComparison {
    static final String VERSION="candidate-hypothesis-removal-20261017-1";
    static final String CONSENT="NEW_USD050_HYPOTHESIS_REMOVAL_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="26ac78c53886ae035b1e5a6051f6492c764b06107414ac5e3787e2bab107abd9";
    static final String RESULTS_SHA="c1126f824641d9e2d9bbc007339c4d53c031f1417c0931c09be9e7bff455afa4";
    private HypothesisRemovalComparison() {}
    static String input(String original) {
        var node=(tools.jackson.databind.node.ObjectNode)JSON.readTree(original);
        check(node.path("candidates").isArray() && node.path("candidates").size()==1,"One frozen candidate required");
        var candidate=(tools.jackson.databind.node.ObjectNode)node.path("candidates").get(0);
        check(candidate.path("hypothesisNotEvidence").isString() && !candidate.path("hypothesisNotEvidence").asText().isBlank(),"Original hypothesis required");
        candidate.remove("hypothesisNotEvidence");return node.toString();
    }
    static String system(String original) {
        if(original.contains("proposedEvidence에 선택되지 않은 허용 원문")) return original;
        return VerificationContractComparison.replaceOnce(original,
                "  또한 proposedEvidence의 anchor 이외 줄에서 실제 연결을 설명하는 CONTEXT 인용을 하나 이상 반환한다.\n",
                "  또한 해당 후보의 허용 raw 중 anchor 이외 줄에서 실제 연결을 설명하는 CONTEXT 인용을 하나 이상 반환한다.\n"
                        +"  정상 해석의 근거는 proposedEvidence에 선택되지 않은 허용 원문에서도 고를 수 있다. 창 밖 근거는 금지다.\n");
    }
    static UtteranceDirectionTrial.Plan plan(UtteranceDirectionTrial.Plan source,String sha) throws Exception {
        check(UtteranceDirectionTrial.compact(source),"Frozen v2/v3 study required");
        var tasks=source.tasks().stream().map(t -> new UtteranceDirectionTrial.Task(t.id(),t.labelStatus(),t.expectedDecision(),
                null,null,system(t.comparisonSystem()),system(t.comparisonSystem()),t.input())).toList();
        var plan=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,sha,source.runtimeSha256(),
                source.normalFixtureSha256(),source.relationFixtureSha256(),tasks);
        UtteranceDirectionTrial.validate(plan);return plan;
    }
    static UtteranceDirectionTrial.Plan prepare(byte[] source,byte[] results) throws Exception {
        check(ReferenceOnlyTrial.sha(source).equals(SOURCE_SHA) && ReferenceOnlyTrial.sha(results).equals(RESULTS_SHA),"Frozen v3 source/results required");
        check(ReferenceOnlyTrial.sha(source).equals(JSON.readTree(results).path("planSha256").asText()),"Source/result hash mismatch");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(source),JSON.readTree(results));
        return plan(JSON.readValue(source,UtteranceDirectionTrial.Plan.class),ReferenceOnlyTrial.sha(source));
    }
}
