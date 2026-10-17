package com.example.oops.analyzer;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

class AssessmentOnlyDiagnosticTest {
    UtteranceDirectionTrial.Task task() throws Exception {
        return SourceUnderstandingComparison.smoke(new SourceUnderstandingComparisonTest().plan());
    }
    CandidateReviewEngine.VerificationResult broken(UtteranceDirectionTrial.Task t) {
        var v=new CompactRelationsComparisonTest().response(t,true).verifications().get(0);
        var a=v.sourceUnderstanding().relations().get(0);
        var reading=new SourceUnderstanding(null,List.of(new SourceUnderstanding.RelationReading(a.segmentId(),a.actType(),
                "OTHER",null,a.about(),a.aboutIds(),a.actionOwner(),a.ownerIds())));
        return new CandidateReviewEngine.VerificationResult(List.of(new CandidateReviewEngine.Verification(v.candidateId(),v.assessment(),null,reading)));
    }
    Map<String,Object> full(UtteranceDirectionTrial.Task t,CandidateReviewEngine.VerificationResult response) {
        return UtteranceDirectionTrial.assess(t,response,null,true,SourceUnderstandingContract.Version.RELATIONS_V3);
    }
    @Test void isolatesAuxiliaryFailureWithoutChangingOutputOrFullRejection() throws Exception {
        var t=task();var response=broken(t);String before=JSON.writeValueAsString(response);var full=full(t,response);
        assertThat(full).containsEntry("accepted",false).containsEntry("failureCode","SOURCE_RELATION_READING_LINK");
        var diagnostic=OfflineDecisionContractAudit.assessmentOnlyDiagnostic(t,response,full);
        assertThat(diagnostic).containsEntry("contractAccepted",true).containsEntry("auxiliaryOnlyRejection",true)
                .containsEntry("modelReexecuted",false).containsEntry("excludedFromModelPerformance",true)
                .containsEntry("semanticTruthAutomaticallyEstablished",false);
        assertThat(JSON.writeValueAsString(response)).isEqualTo(before);
        assertThat(full(t,response)).isEqualTo(full);
    }
    @Test void doesNotHideExistingAssessmentFailureBehindAnAuxiliaryFailure() throws Exception {
        var t=task();var tree=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(broken(t));
        ((tools.jackson.databind.node.ObjectNode)tree.at("/verifications/0/assessment")).put("target","made-up target");
        var response=JSON.treeToValue(tree,CandidateReviewEngine.VerificationResult.class);
        var diagnostic=OfflineDecisionContractAudit.assessmentOnlyDiagnostic(t,response,full(t,response));
        assertThat(diagnostic).containsEntry("contractAccepted",false).containsEntry("contractFailureCode","NON_REVIEW_FIELDS")
                .containsEntry("auxiliaryOnlyRejection",false).containsEntry("bothContractsReject",true);
    }
    @Test void validAuxiliaryOutputDoesNotCreateAnImaginaryImprovement() throws Exception {
        var t=task();var response=new CompactRelationsComparisonTest().response(t,true);
        assertThat(OfflineDecisionContractAudit.assessmentOnlyDiagnostic(t,response,full(t,response)))
                .containsEntry("contractAccepted",true).containsEntry("auxiliaryOnlyRejection",false)
                .containsEntry("bothContractsReject",false);
    }
}
