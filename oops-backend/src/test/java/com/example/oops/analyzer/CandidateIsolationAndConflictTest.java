package com.example.oops.analyzer;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateIsolationAndConflictTest {
    @Test void malformedKnownAnchorDoesNotEraseCompletedNeighbor() {
        var f=new CandidateReviewEngineTest();var context=f.context("그 집 음식은 취향이야","다른 대화");
        f.discovery(List.of("stt-index-0","stt-index-1"),f.proposal("stt-index-0","그 집"),
                new Proposal("stt-index-1","INVALID_AXIS","잘못된 후보",List.of(new Quote("stt-index-1","다른 대화"))));
        f.verification(new Verification("candidate-1",f.assessment("stt-index-0","PASS","그 집")));
        var result=run(f.client,context,24);
        assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(result.unassessedSegmentIds()).containsExactly("stt-index-1");
        assertThat(result.diagnostics().segments()).anySatisfy(t -> {
            assertThat(t.segmentId()).isEqualTo("stt-index-0");assertThat(t.state().name()).isEqualTo("PASS");
        });
    }
    @Test void unknownNullOrUnreviewedAnchorsKeepConservativeBatchUncertainty() {
        var ids=List.of("stt-index-0","stt-index-1");
        for(var p:Arrays.asList((Proposal)null,new Proposal(null,"TARGET_TREATMENT","가설",List.of()),
                new Proposal("outside","TARGET_TREATMENT","가설",List.of()),new Proposal("stt-index-1","TARGET_TREATMENT","가설",List.of()))) {
            var unresolved=new HashSet<String>();markProposalUnresolved(p,ids,List.of("stt-index-0"),unresolved);
            assertThat(unresolved).containsExactlyInAnyOrderElementsOf(ids);
        }
    }
    @Test void relationModeFailureIsLocalizedButMissingEnvelopeIsNot() {
        var f=new CandidateReviewEngineTest();var ids=List.of("stt-index-0","stt-index-1");
        var malformed=new Proposal("stt-index-1","TARGET_TREATMENT","구형 가설",List.of(new Quote("stt-index-1","그 집")),
                new SourceRelations(List.of(),List.of(),List.of(),List.of(),List.of()));
        f.discovery(ids,malformed);
        var result=run(f.client,f.context("일반 설명","그 집은 조용하다"),24);
        assertThat(result.unassessedSegmentIds()).containsExactly("stt-index-1");
        reset(f.client);when(f.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenReturn(Optional.empty());
        assertThat(run(f.client,f.context("일반 설명","그 집은 조용하다"),24).unassessedSegmentIds())
                .containsExactlyInAnyOrderElementsOf(ids);
    }
    @Test void sameEvidenceDivergenceIsDiagnosticOnlyAndDoesNotSuppressWarning() {
        var f=new CandidateReviewEngineTest();var context=f.context("그 집 이용자는 수준이 낮아");
        var p=f.proposal("stt-index-0","그 집");
        f.discovery(List.of("stt-index-0"),p,new Proposal(p.anchorId(),p.axis(),"표현이 다른 검토 가설",p.evidence()));
        f.verification(new Verification("candidate-1",f.assessment(p.anchorId(),"PASS","그 집")),
                new Verification("candidate-2",f.assessment(p.anchorId(),"REVIEW_REQUIRED","그 집")));
        var result=run(f.client,context,24);
        assertThat(result.findings()).hasSize(1);assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(result.diagnostics().candidatePipeline().decisionConflicts()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo("SAME_EVIDENCE_POTENTIAL_DISAGREEMENT");
            assertThat(t.candidateIds()).containsExactly("candidate-1","candidate-2");
            assertThat(t.decisions()).containsExactly("PASS","REVIEW_REQUIRED");
            assertThat(JSON.writeValueAsString(t)).doesNotContain("수준이 낮아","검토 가설");
        });
    }
    @Test void differentEvidenceWindowAxisOrStageIsNotDeclaredSameIssue() {
        var f=new CandidateReviewEngineTest();var context=f.context("그 집 설명","그 집 추가 설명");
        var p=f.proposal("stt-index-0","그 집");
        var base=new Candidate("c1",p,context.reviewInput().segments(),false,false);
        var pass=validate(base,f.assessment(p.anchorId(),"PASS","그 집"),context.reviewInput(),0).observation();
        var review=validate(base,f.assessment(p.anchorId(),"REVIEW_REQUIRED","그 집"),context.reviewInput(),0).observation();
        var others=List.of(new Candidate("c2",new Proposal(p.anchorId(),"EXPRESSION_CONTENT",p.reason(),p.evidence()),base.raw(),false,false),
                new Candidate("c2",new Proposal(p.anchorId(),p.axis(),p.reason(),List.of(new Quote(p.anchorId(),"그 집"),new Quote("stt-index-1","추가"))),base.raw(),false,false),
                new Candidate("c2",p,base.raw().subList(0,1),false,false));
        for(var other:others) { var tracker=new CandidateDecisionConflicts();tracker.record(base,pass,"TEXT");tracker.record(other,review,"TEXT");assertThat(tracker.traces()).isEmpty(); }
        var tracker=new CandidateDecisionConflicts();tracker.record(base,pass,"TEXT");tracker.record(new Candidate("c2",p,base.raw(),false,false),review,"VISUAL");
        assertThat(tracker.traces()).isEmpty();
    }
}
