package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextBoundaryTrialTest {
    @TempDir Path temporary;
    @Test void budgetReservesEveryHttpAttemptAndRefusesTheNextBeforeDispatch() throws Exception {
        var budget=new TextBoundaryTrial.Budget(.0083,.10,.50,temporary);
        budget.reserve(1); budget.reserve(1);
        assertThat(budget.attempts).isEqualTo(2);
        assertThat(Files.readString(temporary.resolve("reservation.json"))).contains("actualBillingConfirmed");
        assertThatThrownBy(()->budget.reserve(1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.exhausted).isTrue(); assertThat(budget.attempts).isEqualTo(2);
        assertThatThrownBy(()->new TextBoundaryTrial.Budget(2,.1,.5,temporary)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fixtureHasSixNormalAndThreePositiveDevelopmentCasesWithTwoRepeats() throws Exception {
        var suite=TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json"));
        assertThat(suite.cases().stream().filter(c->c.expected().equals("PASS"))).hasSize(6);
        assertThat(suite.cases().stream().filter(c->c.expected().equals("REVIEW_REQUIRED"))).hasSize(3);
        assertThat(suite.repeats()).isEqualTo(2);
        assertThat(suite.cases().stream().filter(c->c.id().startsWith("P-B")).findFirst().orElseThrow().speech())
                .contains("그래도 맛은 참 건강하다","할 때 만들었던 유기농 햄버거");
    }
    @Test void normalFixtureLabelsDoNotEnterDiscoveryInput() throws Exception {
        var suite=TextBoundaryTrial.readSuite(Path.of("src/test/resources/evals/text-boundaries-37.json"));
        for(var c:suite.cases()) if(c.expected().equals("PASS")) {
            var f=new CandidateReviewEngineTest();
            List<String> ids=new ArrayList<>(); for(int i=0;i<c.speech().size();i++) ids.add("stt-index-"+i);
            f.discovery(ids);
            var result=CandidateReviewEngine.run(f.client,f.context(c.speech().toArray(String[]::new)),24);
            assertThat(result.unassessedSegmentIds()).isEmpty();
            var request=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(f.client).completeAsJson(anyString(),request.capture(),eq(CandidateReviewEngine.Discovery.class));
            assertThat(request.getValue()).doesNotContain("expected",c.id(),"USER_APPROVED");
        }
    }
}
