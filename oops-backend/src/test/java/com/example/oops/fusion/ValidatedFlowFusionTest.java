package com.example.oops.fusion;

import com.example.oops.domain.*;
import com.example.oops.dto.TimelineEventDto;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ValidatedFlowFusionTest {
    RiskFinding grounded(String label, String type, FindingSupport.Quote target, FindingSupport.Quote... context) {
        var f = finding("a", 23500, .7, context);
        org.springframework.test.util.ReflectionTestUtils.setField(f, "target", label);
        var quotes = new ArrayList<>(f.validatedSupports().get(0).quotes());
        quotes.add(target);
        f.recordValidatedSupports(List.of(new FindingSupport("a", type, quotes)));
        return f;
    }
    @Test void differentDisplayLabelsMergeWithSameRawTargetMention() {
        var a = grounded("이곳의 음식 선택지와 생활", "REGION",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "원문", "TARGET"));
        var b = grounded("영양 지역의 음식·생활 선택지", "REGION", quote("a", 23500, "TARGET"));
        var r = new FindingFusionService().fuse(List.of(a, b));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getOccurrenceCount()).isOne();
        assertThat(r.get(0).getMergedCount()).isEqualTo(2);
    }
    @Test void nearbyRegionDeixisNeedsSharedContextAndPreservesThreeSupports() {
        var x = quote("x", 26000, "CONTEXT"); var y = quote("y", 36500, "CONTEXT");
        var here = new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "여기", "TARGET");
        var previous = new FindingSupport.Quote("previous", TimelineEventType.SPEECH, 22500, 23500, "여기 봐봐", "TARGET");
        var a = grounded("이곳의 음식 선택지와 생활", "REGION", here, x, y);
        var b = grounded("영양 지역의 음식·생활 선택지", "REGION", here, x, y);
        var c = grounded("해당 장소의 생활 선택지", "REGION", previous, x, y);
        var r = new FindingFusionService().fuse(List.of(a, b, c));
        assertThat(r).hasSize(1);
        var dto = TimelineEventDto.from(r.get(0), null, null, null);
        assertThat(dto.supportCount()).isEqualTo(3); assertThat(dto.occurrences()).isOne();
        assertThat(dto.relatedEvidence()).contains(previous);
    }
    @Test void proximityWithoutSharedContextOrWithDifferentTypeCannotMerge() {
        var here = new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "여기", "TARGET");
        var previous = new FindingSupport.Quote("previous", TimelineEventType.SPEECH, 22500, 23500, "여기 봐봐", "TARGET");
        assertThat(new FindingFusionService().fuse(List.of(
                grounded("이곳의 음식 선택지와 생활", "REGION", here),
                grounded("해당 장소의 생활 선택지", "REGION", previous)))).hasSize(2);
        assertThat(new FindingFusionService().fuse(List.of(
                grounded("이곳의 음식 선택지와 생활", "REGION", here),
                grounded("영양 지역의 음식·생활 선택지", "PRODUCT", here)))).hasSize(2);
    }
    @Test void differentExplicitTargetsAndDistantDeixisStaySeparateDespiteSharedContext() {
        var x = quote("x", 26000, "CONTEXT"); var y = quote("y", 36500, "CONTEXT");
        var a = grounded("이곳의 음식 선택지와 생활", "REGION",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "서울", "TARGET"), x, y);
        var b = grounded("다른 지역의 생활 조건", "REGION",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "부산", "TARGET"), x, y);
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        a = grounded("이곳의 음식 선택지와 생활", "REGION",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "여기", "TARGET"), x, y);
        b = grounded("다른 지역의 생활 조건", "REGION",
                new FindingSupport.Quote("far", TimelineEventType.SPEECH, 15000, 16000, "여기", "TARGET"), x, y);
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
    @Test void IdenticalDisplayLabelsCannotOverrideConflictingTargetQuotes() {
        var a = grounded("같은 표시 이름", "GROUP",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "첫 집단", "TARGET"));
        var b = grounded("같은 표시 이름", "GROUP",
                new FindingSupport.Quote("a", TimelineEventType.SPEECH, 23500, 24500, "다른 집단", "TARGET"));
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
    @Test void oneSharedTargetCannotHideAnAdditionalUnmatchedTarget() {
        var a = grounded("첫 대상", "GROUP", quote("a", 23500, "TARGET"));
        var b = grounded("다른 대상", "GROUP", quote("a", 23500, "TARGET"));
        var extra = new ArrayList<>(b.validatedSupports().get(0).quotes());
        extra.add(new FindingSupport.Quote("other", TimelineEventType.SPEECH, 22000, 23000, "다른 집단", "TARGET"));
        b.recordValidatedSupports(List.of(new FindingSupport("a", "GROUP", extra)));
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
    FindingSupport.Quote quote(String id, long at, String role) {
        return new FindingSupport.Quote(id, TimelineEventType.SPEECH, at, at + 1000, "원문 " + id, role);
    }
    RiskFinding finding(String id, long at, double score, FindingSupport.Quote... context) {
        var f = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.BELITTLEMENT)
                .source(EvidenceSource.SUBTITLE).startMs(at).endMs(at + 1000).score(score)
                .text("원문 " + id).target("해당 지역의 음식 선택지").reason("검증된 대상 낮춤 연결").build();
        var quotes = new ArrayList<>(List.of(quote(id, at, "PRIMARY")));
        quotes.addAll(List.of(context));
        f.recordValidatedSupports(List.of(new FindingSupport(id, "REGION", quotes)));
        return f;
    }
    @Test void duplicateEvaluatorsAtOneAnchorAreOneOccurrenceWithTwoSupports() {
        var r = new FindingFusionService().fuse(List.of(finding("a", 10000, .7), finding("a", 10000, .8))).get(0);
        var dto = TimelineEventDto.from(r, null, null, null);
        assertThat(dto.occurrences()).isOne(); assertThat(dto.supportCount()).isEqualTo(2);
        assertThat(dto.relatedEvidence()).hasSize(1);
        assertThat(r.getScore()).isEqualTo(.8);
    }
    @Test void separateAnchorsWithExplicitLinkAndTwoSharedContextQuotesMergeAndPersistMetadata() {
        var a = finding("a", 10000, .7, quote("b", 25000, "CONTEXT"), quote("x", 18000, "CONTEXT"), quote("y", 20000, "CONTEXT"));
        var b = finding("b", 25000, .8, quote("x", 18000, "CONTEXT"), quote("y", 20000, "CONTEXT"));
        var result = new FindingFusionService().fuse(List.of(a, b));
        assertThat(result).hasSize(1);
        var r = result.get(0);
        assertThat(r.getOccurrenceCount()).isEqualTo(2); assertThat(r.getMergedCount()).isEqualTo(2);
        assertThat(r.getStartMs()).isEqualTo(10000); assertThat(r.getEndMs()).isEqualTo(26000);
        assertThat(r.getOccurrenceTimes()).contains("00:10", "00:25");
        assertThat(r.representativeStartMs()).isEqualTo(25000);
        assertThat(TimelineEventDto.from(r, null, null, null).relatedEvidence()).anyMatch(q -> q.segmentId().equals("a"));
        var reloaded = RiskFinding.builder().category(RiskCategory.BELITTLEMENT).build();
        org.springframework.test.util.ReflectionTestUtils.setField(reloaded, "validatedSupportJson", r.getValidatedSupportJson());
        assertThat(reloaded.validatedSupports()).isEqualTo(r.validatedSupports());
    }
    @Test void overlappingContextsWithoutAnAnchorLinkRemainSeparate() {
        var r = new FindingFusionService().fuse(List.of(
                finding("a", 10000, .7, quote("x", 18000, "CONTEXT"), quote("y", 20000, "CONTEXT")),
                finding("b", 25000, .7, quote("x", 18000, "CONTEXT"), quote("y", 20000, "CONTEXT"))));
        assertThat(r).hasSize(2);
    }
    @Test void linkedAnchorWithoutTwoSharedContextSegmentsRemainsSeparate() {
        assertThat(new FindingFusionService().fuse(List.of(
                finding("a", 10000, .7, quote("b", 25000, "CONTEXT")), finding("b", 25000, .8)))).hasSize(2);
    }
    @Test void differentTargetAndDifferentCategoryCannotMergeDespiteSameEvidence() {
        var a = finding("a", 10000, .7); var b = finding("a", 10000, .8);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "target", "다른 사업자의 직원");
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
        b = finding("a", 10000, .8);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "category", RiskCategory.GRAPHIC_METAPHOR);
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
    @Test void completeLinkPreventsTransitiveMergeAcrossDifferentFlows() {
        var x = quote("x", 15000, "CONTEXT"); var y = quote("y", 17000, "CONTEXT");
        var a = finding("a", 10000, .7, quote("b", 20000, "CONTEXT"), x, y);
        var b = finding("b", 20000, .7, quote("c", 30000, "CONTEXT"), x, y);
        var c = finding("c", 30000, .7, x, y);
        assertThat(new FindingFusionService().fuse(List.of(a, b, c))).hasSize(2);
    }
    @Test void legacyReportsKeepCountsButFreshTargetNameOnlyCandidatesDoNotMerge() {
        var a = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.BELITTLEMENT)
                .source(EvidenceSource.SUBTITLE).text("첫 표현").target("메뉴").startMs(1000).endMs(2000).build();
        var b = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.BELITTLEMENT)
                .source(EvidenceSource.SUBTITLE).text("다른 쟁점").target("메뉴").startMs(2000).endMs(3000).build();
        a.applyFusion(1, false, 3);
        assertThat(TimelineEventDto.from(a, null, null, null).occurrences()).isEqualTo(3);
        assertThat(new FindingFusionService().fuse(List.of(a, b))).hasSize(2);
    }
}
