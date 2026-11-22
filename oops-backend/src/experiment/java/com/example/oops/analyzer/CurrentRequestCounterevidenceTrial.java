package com.example.oops.analyzer;

import java.nio.file.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Exact newly approved 48-call execution, reusing the bounded fixed-verification executor. */
public final class CurrentRequestCounterevidenceTrial {
    static final String PLAN_SHA="7f54f8fba2e975d9d8fb0a0fa480624385f6a1b0dd4c6f0eeb1604035000a121";
    static final String CONSENT="NEW_USD050_CURRENT_REQUEST_COUNTEREVIDENCE_TEXT_EGRESS_APPROVED";
    private CurrentRequestCounterevidenceTrial() {}
    static CurrentRequestCounterevidencePlan.Plan authorized(byte[] bytes,byte[] baseline,byte[] inputs,byte[] labels,
                                                           String sha,String consent) throws Exception {
        check(PLAN_SHA.equals(sha) && sha.equals(ReferenceOnlyTrial.sha(bytes)) && CONSENT.equals(consent),
                "Fresh exact-plan counterevidence USD050/text consent required");
        var plan=CurrentRequestCounterevidencePlan.prepare(baseline,inputs,labels);
        check(JSON.readTree(bytes).equals(JSON.valueToTree(plan)),"Current engine or frozen comparison changed");
        return plan;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==6,"Plan, baseline, approved bundle, approved sha, fresh consent and NEW private output required");
        byte[] bytes=OfflineDecisionContractAudit.bounded(Path.of(args[0]));Path bundle=Path.of(args[2]);
        var plan=authorized(bytes,OfflineDecisionContractAudit.bounded(Path.of(args[1])),
                OfflineDecisionContractAudit.bounded(bundle.resolve("inputs.json")),
                OfflineDecisionContractAudit.bounded(bundle.resolve("labels.json")),args[3],args[4]);
        DefaultVerificationTrial.runFrozen(new DefaultVerificationTrial.FrozenRun(PLAN_SHA,CONSENT,plan.model(),48,3,
                "current-request-counterevidence-results-1","USER_APPROVED_FROZEN_COUNTEREVIDENCE_COMPARISON_20261018",plan.requests()),
                bytes,Path.of(args[0]),Path.of(args[5]));
    }
}
