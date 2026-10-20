package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MechanismCandidateShadowTest {
    private ReviewInput.Segment line(String id, long start, String text) {
        return new ReviewInput.Segment(id, TimelineEventType.SPEECH, start, start + 1000, text, null);
    }
    @Test void retainsExactEvidenceAndIsRepeatable() {
        var input = new ReviewInput(List.of(line("a",0,"이 메뉴는 의미가 없어"),line("b",1000,"주는대로 먹어야 돼")));
        var result = MechanismCandidateShadow.inspect(input);
        assertEquals(result, MechanismCandidateShadow.inspect(input));
        assertEquals(1,result.candidates().size());
        var c = result.candidates().get(0);
        assertEquals("a",c.anchorId()); assertEquals(List.of("a","b"),c.contextIds());
        c.matchedSignals().forEach(s -> assertEquals(s.matchedText(), input.find(s.segmentId()).orElseThrow()
                .text().substring(s.startOffset(),s.endOffset())));
    }
    @Test void ordinaryNegativeReviewAloneDoesNotSupplyCombination() {
        assertTrue(MechanismCandidateShadow.inspect(new ReviewInput(List.of(line("a",0,"맛이 별로고 돈이 아까워"))))
                .candidates().isEmpty());
    }
    @Test void gapsAndWindowBudgetDoNotJoinSeparateSignals() {
        assertTrue(MechanismCandidateShadow.inspect(new ReviewInput(List.of(line("a",0,"의미가 없어"),
                line("b",10000,"주는대로 먹어야 돼")))).candidates().isEmpty());
    }
    @Test void quotedOrNormalSignalsRemainCandidatesNotAutomaticWarnings() {
        var input = new ReviewInput(List.of(line("a",0,"메뉴는 의미가 없고 주는대로 먹어야 된다는 말을 비판합니다")));
        assertEquals(1,MechanismCandidateShadow.inspect(input).candidates().size());
    }
    @Test void foodCompoundAndCaptionDoNotBecomeBodyCandidate() {
        var caption = new ReviewInput.Segment("b",TimelineEventType.CAPTION,0,1000,"살을 뜯는",null);
        assertTrue(MechanismCandidateShadow.inspect(new ReviewInput(List.of(line("a",0,"목살을 뜯는 요리"),caption)))
                .candidates().isEmpty());
    }
    @Test void bodyAndGroupKeepSeparateMechanisms() {
        var result = MechanismCandidateShadow.inspect(new ReviewInput(List.of(line("a",0,"사람들 모두 무능하다"),
                line("b",1000,"피부를 찢는 비유"))));
        assertEquals(2,result.candidates().size());
        assertEquals("collective-evaluation",result.candidates().get(0).mechanismId());
    }
    @Test void irrelevantPrefixIsRemovedAndSameAnchorIsDeduplicated() {
        var result = MechanismCandidateShadow.inspect(new ReviewInput(List.of(line("z",0,"안녕하세요"),
                line("a",1000,"의미가 없어"),line("b",2000,"주는대로 먹어야 돼"))));
        assertEquals(1,result.candidates().size());
        assertEquals(List.of("a","b"),result.candidates().get(0).contextIds());
    }
    @Test void longSingleSegmentIsNotSilentlyScannedOutsideWindowBudget() {
        var row = new ReviewInput.Segment("a",TimelineEventType.SPEECH,0,21000,"피부를 찢는 비유",null);
        var result = MechanismCandidateShadow.inspect(new ReviewInput(List.of(row)));
        assertTrue(result.candidates().isEmpty()); assertEquals(1,result.overlongSegments());
    }
    @Test void normalMenuExplanationStillNeedsLaterJudgment() {
        var input = new ReviewInput(List.of(line("a",0,"선택 메뉴가 하나라 메뉴판은 의미가 없어요"),
                line("b",1000,"오늘은 주는대로 먹어야 돼요. 미리 안내받고 동의했어요")));
        assertEquals(1,MechanismCandidateShadow.inspect(input).candidates().size());
    }
    @Test void criticalGroupQuotationIsNotSilentlySuppressed() {
        var input = new ReviewInput(List.of(line("a",0,"주민 모두 무능하다는 표현을 인용해 비판합니다")));
        assertEquals(1,MechanismCandidateShadow.inspect(input).candidates().size());
    }
    @Test void educationalBodyMentionIsNotAWarningDecision() {
        var input = new ReviewInput(List.of(line("a",0,"피부를 찢는 사고를 예방하는 안전 교육입니다")));
        var result = MechanismCandidateShadow.inspect(input);
        assertEquals("RULE_SHADOW",result.candidates().get(0).origin());
        assertEquals("graphic-body-image",result.candidates().get(0).mechanismId());
    }
}
