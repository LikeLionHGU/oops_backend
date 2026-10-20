package com.example.oops.fusion;

import com.example.oops.domain.*;
import com.example.oops.dto.TimelineEventDto;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Structural merge regression, not approved semantic labels or model accuracy. */
class ReciprocalGraphicFusionTest {
    FindingSupport.Quote quote(String id, long time, String role) {
        return new FindingSupport.Quote(id, TimelineEventType.SPEECH, time, time + 1000, "합성 원문 " + id, role);
    }
    RiskFinding finding(String primary, long at, String context, long contextAt, double score) {
        var f = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.GRAPHIC_METAPHOR)
                .source(EvidenceSource.SUBTITLE).startMs(at).endMs(at + 1000).score(score)
                .text("합성 원문 " + primary).reason("같은 두 줄을 근거로 한 구조 테스트").build();
        f.recordValidatedSupports(List.of(new FindingSupport(primary, null,
                List.of(quote(primary, at, "PRIMARY"), quote(context, contextAt, "CONTEXT")))));
        return f;
    }
    @Test void swappedPrimaryContextBecomesOneOccurrenceWithoutLosingEvidenceOrIncreasingScore() {
        var a = finding("a", 1000, "b", 3000, .6);
        var b = finding("b", 3000, "a", 1000, .8);
        var result = new FindingFusionService().fuse(List.of(a, b));
        assertThat(result).hasSize(1);
        var r = result.get(0); var dto = TimelineEventDto.from(r, null, null, null);
        assertThat(dto.occurrences()).isOne(); assertThat(dto.supportCount()).isEqualTo(2);
        assertThat(dto.relatedEvidence()).hasSize(4);
        assertThat(r.validatedSupports()).hasSize(2);
        assertThat(r.getScore()).isEqualTo(.8); assertThat(r.representativeStartMs()).isEqualTo(3000);
        assertThat(r.getStartMs()).isEqualTo(1000); assertThat(r.getEndMs()).isEqualTo(4000);
        assertThat(r.getOccurrenceTimes()).isNull();
    }
    @Test void repeatedEvaluationOfBothAnchorsIsStillOneOccurrence() {
        var r = new FindingFusionService().fuse(List.of(finding("a", 1000, "b", 3000, .6),
                finding("b", 3000, "a", 1000, .8), finding("b", 3000, "a", 1000, .7)));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getOccurrenceCount()).isOne(); assertThat(r.get(0).getMergedCount()).isEqualTo(3);
    }
    @Test void sharedContextOrOneWayLinkCannotMerge() {
        assertThat(new FindingFusionService().fuse(List.of(finding("a", 1000, "x", 2000, .6),
                finding("b", 3000, "x", 2000, .8)))).hasSize(2);
        assertThat(new FindingFusionService().fuse(List.of(finding("a", 1000, "b", 3000, .6),
                finding("b", 3000, "x", 2000, .8)))).hasSize(2);
    }
    @Test void extraEvidenceOrAnotherSupportCannotHideDifferentIssue() {
        var a = finding("a", 1000, "b", 3000, .6); var b = finding("b", 3000, "a", 1000, .8);
        var quotes = new ArrayList<>(b.validatedSupports().get(0).quotes()); quotes.add(quote("x", 2000, "CONTEXT"));
        b.recordValidatedSupports(List.of(new FindingSupport("b", null, quotes)));
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        b = finding("b", 3000, "a", 1000, .8);
        b.recordValidatedSupports(List.of(b.validatedSupports().get(0),
                new FindingSupport("b", null, List.of(quote("b", 3000, "PRIMARY"), quote("x", 2000, "CONTEXT")))));
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
    @Test void sourceQuoteOrTimingMismatchPreventsMerge() {
        var a = finding("a", 1000, "b", 3000, .6); var b = finding("b", 3000, "a", 1000, .8);
        b.recordValidatedSupports(List.of(new FindingSupport("b", null, List.of(quote("b", 3000, "PRIMARY"),
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 1000, 2000, "다른 원문", "CONTEXT")))));
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        assertThat(new FindingFusionService().fuse(List.of(a, finding("b", 3000, "a", 1001, .8)))).hasSize(2);
    }
    @Test void otherCategoriesTargetsAndDistantPairsKeepExistingPolicy() {
        var a = finding("a", 1000, "b", 3000, .6); var b = finding("b", 3000, "a", 1000, .8);
        org.springframework.test.util.ReflectionTestUtils.setField(a, "category", RiskCategory.VIOLENCE);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "category", RiskCategory.VIOLENCE);
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        a = finding("a", 1000, "b", 3000, .6); b = finding("b", 3000, "a", 1000, .8);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "target", "별도 대상");
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        assertThat(new FindingFusionService().fuse(List.of(finding("a", 1000, "b", 15000, .6),
                finding("b", 15000, "a", 1000, .8)))).hasSize(2);
    }
    @Test void repeatedWordsAtDifferentRawLocationsAreNotCollapsed() {
        var first = finding("a", 1000, "b", 3000, .6); var second = finding("c", 5000, "d", 7000, .8);
        assertThat(new FindingFusionService().fuse(List.of(first, second))).hasSize(2);
    }
    @Test void redundantPrimaryAsContextDoesNotBlockPairAndRemainsOnCard() {
        var a=finding("a",1000,"b",3000,.6);var b=finding("b",3000,"a",1000,.8);
        var quotes=new ArrayList<>(b.validatedSupports().get(0).quotes());quotes.add(quote("b",3000,"CONTEXT"));
        var support=new FindingSupport("b",null,quotes);
        b.recordValidatedSupports(List.of(support));
        var result=new FindingFusionService().fuse(List.of(a,b));
        assertThat(result).hasSize(1);assertThat(result.get(0).getOccurrenceCount()).isOne();
        assertThat(result.get(0).validatedSupports()).contains(support).hasSize(2);
        assertThat(result.get(0).getScore()).isEqualTo(.8);
    }
    @Test void differentTimeTextOrTargetRoleOnAnchorIsNotRedundantContext() {
        for(var extra:List.of(quote("b",3001,"CONTEXT"),
                new FindingSupport.Quote("b",TimelineEventType.SPEECH,3000,4000,"다른 인용","CONTEXT"),quote("b",3000,"TARGET"))) {
            var a=finding("a",1000,"b",3000,.6);var b=finding("b",3000,"a",1000,.8);
            var quotes=new ArrayList<>(b.validatedSupports().get(0).quotes());quotes.add(extra);
            b.recordValidatedSupports(List.of(new FindingSupport("b",null,quotes)));
            assertThat(new FindingFusionService().fuse(List.of(a,b))).hasSize(2);
        }
    }
}
