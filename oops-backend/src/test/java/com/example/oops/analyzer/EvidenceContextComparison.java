package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Study-specific evidence salience intervention; not a runtime language/keyword rule. */
final class EvidenceContextComparison {
    static final String VERSION="definition-evidence-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_DEFINITION_EVIDENCE_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="757bcdc8a0e91e9158baf4ae4cc600f29900aae752153b4eeb845ed53f3c0baa";
    static final String RESULTS_SHA="c360c74bd29740d8b846891d7fbb80c79e441572faa09e8870da582514177135";
    static final Set<String> TARGETS=Set.of("groq-v4-166","groq-v4-178");
    static final String CONTEXT_ID="groq-v4-165";
    private EvidenceContextComparison() {}

    static String input(String caseId,String original) {
        if(!TARGETS.contains(caseId)) return original;
        var node=(tools.jackson.databind.node.ObjectNode)JSON.readTree(original);
        check(node.path("candidates").size()==1,"One frozen candidate required");
        var candidate=(tools.jackson.databind.node.ObjectNode)node.path("candidates").get(0);
        var allowed=new HashSet<String>();candidate.path("segmentIds").forEach(id -> allowed.add(id.asText()));
        var matching=new ArrayList<tools.jackson.databind.JsonNode>();
        node.path("raw").forEach(s -> {if(CONTEXT_ID.equals(s.path("id").asText())) matching.add(s);});
        check(allowed.contains(CONTEXT_ID) && matching.size()==1 && !matching.get(0).path("text").asText().isBlank(),"Existing allowed context source required");
        var evidence=candidate.path("proposedEvidence");
        check(evidence.isArray() && !evidence.isEmpty() && evidence.size()<8,"Bounded original proposed evidence required");
        evidence.forEach(e -> check(!CONTEXT_ID.equals(e.path("segmentId").asText()),"Context already proposed"));
        var supplemented=JSON.createArrayNode();
        supplemented.addObject().put("segmentId",CONTEXT_ID).put("quote",matching.get(0).path("text").asText());
        evidence.forEach(e -> supplemented.add(e.deepCopy()));candidate.set("proposedEvidence",supplemented);
        return node.toString();
    }

    static UtteranceDirectionTrial.Plan prepare(byte[] sourceBytes,byte[] resultBytes) throws Exception {
        var source=JSON.readValue(sourceBytes,UtteranceDirectionTrial.Plan.class);
        check(UtteranceDirectionTrial.neutral(source),"Frozen neutral study source required");
        OfflineDecisionContractAudit.auditDirectWriting(JSON.readTree(sourceBytes),JSON.readTree(resultBytes));
        check(ReferenceOnlyTrial.sha(sourceBytes).equals(JSON.readTree(resultBytes).path("planSha256").asText()),"Frozen source/result hash mismatch");
        var plan=new UtteranceDirectionTrial.Plan(VERSION,source.model(),2,.50,ReferenceOnlyTrial.sha(sourceBytes),
                source.runtimeSha256(),source.normalFixtureSha256(),source.relationFixtureSha256(),source.tasks());
        UtteranceDirectionTrial.validate(plan);return plan;
    }
}
