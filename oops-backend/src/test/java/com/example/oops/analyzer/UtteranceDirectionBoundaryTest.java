package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Free source-preservation tests only. A mock verifier cannot establish semantic accuracy. */
class UtteranceDirectionBoundaryTest {
    record Boundary(String id,String relationDraft,String unsupportedInference,List<String> speech) {}
    record Suite(String schemaVersion,String labelStatus,List<Boundary> cases) {}
    Suite fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/utterance-direction-boundaries.json")) {
            return JSON.readValue(Objects.requireNonNull(in).readAllBytes(),Suite.class);
        }
    }
    @Test void relationDraftsDoNotAssertControversyVerdictsOrPromoteSyntheticData() throws Exception {
        var suite=fixture();
        assertThat(suite.schemaVersion()).isEqualTo("utterance-direction-boundaries-1");
        assertThat(suite.labelStatus()).contains("DRAFT","NOT_CONTROVERSY_GOLD","NOT_PROMOTED");
        assertThat(suite.cases()).hasSize(6);
        assertThat(suite.cases()).extracting(Boundary::id).doesNotHaveDuplicates();
        assertThat(suite.cases()).extracting(Boundary::relationDraft).containsExactlyInAnyOrder(
                "SELF_DISCLOSURE_OFFER","OTHER_DISCLOSURE_REQUEST","TERM_EXPLANATION","CRITICAL_QUOTATION",
                "ACTOR_UNRESOLVED","REFUSAL_PRESENT");
        assertThat(JSON.writeValueAsString(suite)).doesNotContain("expectedDecision","REVIEW_REQUIRED","PASS");
    }
    @Test void sourceWordingOrderTimesAndIdsSurviveDiscoveryAndVerificationWithoutAnnotationLeakage() throws Exception {
        for(var boundary:fixture().cases()) {
            var f=new CandidateReviewEngineTest();var context=f.context(boundary.speech().toArray(String[]::new));
            var ids=java.util.stream.IntStream.range(0,boundary.speech().size()).mapToObj(i -> "stt-index-"+i).toList();
            // Intentionally wrong hypothesis/quotes: they must not overwrite actual raw evidence.
            var proposal=new Proposal(ids.get(0),"TARGET_TREATMENT",boundary.unsupportedInference(),
                    ids.stream().map(id -> new Quote(id,"상대에게 보여 달라고 요구했다")).toList());
            when(f.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(call -> {
                var request=JSON.readTree((String)call.getArgument(1));
                assertThat(request.path("primaryIds")).isEqualTo(JSON.valueToTree(ids));
                return Optional.of(new Discovery(ids,List.of(proposal),List.of(),false));
            });
            when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class))).thenAnswer(call -> {
                var request=JSON.readTree((String)call.getArgument(1));
                var raw=request.path("raw");
                assertThat(raw.size()).isEqualTo(ids.size());
                for(int i=0;i<ids.size();i++) {
                    assertThat(raw.get(i).path("id").asText()).isEqualTo(ids.get(i));
                    assertThat(raw.get(i).path("text").asText()).isEqualTo(boundary.speech().get(i));
                    assertThat(raw.get(i).path("startMs").asLong()).isEqualTo(i*2000L);
                    assertThat(raw.get(i).path("endMs").asLong()).isEqualTo(i*2000L+1500);
                    assertThat(request.at("/candidates/0/proposedEvidence").get(i).path("quote").asText())
                            .isEqualTo(boundary.speech().get(i));
                }
                assertThat(request.at("/candidates/0/hypothesisNotEvidence").asText()).isEqualTo(boundary.unsupportedInference());
                assertThat(request.toString()).doesNotContain("relationDraft","unsupportedInference",boundary.relationDraft(),"NOT_CONTROVERSY_GOLD");
                return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                        f.linkedPass(ids.get(0),boundary.speech().get(0),ids.get(1),boundary.speech().get(1))))));
            });
            var result=run(f.client,context,24);
            assertThat(result.status().name()).isEqualTo("SUCCESS");
            verify(f.client,times(1)).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
            assertThat(context.reviewInput().segments().stream().filter(s -> s.type()==com.example.oops.domain.TimelineEventType.SPEECH)
                    .map(ReviewInput.Segment::text).toList()).containsExactlyElementsOf(boundary.speech());
        }
    }
}
