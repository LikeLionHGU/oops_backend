package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateUnionShadowTest {
    private ReviewInput.Segment row(String id,long start,String text) {
        return new ReviewInput.Segment(id,TimelineEventType.SPEECH,start,start+1000,text,null);
    }
    private CandidateUnionShadow.AiCandidate ai(ReviewInput input,String id,String anchor,String... evidence) {
        return new CandidateUnionShadow.AiCandidate(id,CandidateUnionShadow.snapshotHash(input),anchor,"TARGET_TREATMENT",List.of(evidence));
    }
    @Test void unionKeepsRuleEvenWhenAiReturnsNothing() {
        var input=new ReviewInput(List.of(row("a",0,"의미가 없어"),row("b",1000,"주는대로 먹어야 돼")));
        var plan=CandidateUnionShadow.plan(input,List.of(),24);
        assertEquals(1,plan.entries().size()); assertEquals(CandidateUnionShadow.State.READY,plan.entries().get(0).state());
        assertEquals(CandidateUnionShadow.Origin.RULE,plan.entries().get(0).candidate().origin());
    }
    @Test void contextIncludesBeforeAfterAndDoesNotPromoteSignalsIntoVerdicts() {
        var input=new ReviewInput(List.of(row("before",0,"오늘은 선택 메뉴가 하나입니다"),row("a",1000,"의미가 없어"),
                row("b",2000,"주는대로 먹어야 돼"),row("after",3000,"안내받고 동의한 내용입니다")));
        var entry=CandidateUnionShadow.plan(input,List.of(),24).entries().get(0);
        assertEquals(List.of("before","a","b","after"),entry.raw().stream().map(ReviewInput.Segment::id).toList());
        assertEquals(List.of("a","b"),entry.candidate().evidenceIds());
    }
    @Test void sourceDistinctIssuesAtSameAnchorAreNotSemanticallyMerged() {
        var input=new ReviewInput(List.of(row("a",0,"의미가 없어 주는대로 먹어야 돼")));
        var a=ai(input,"proposal-a","a","a"); var b=ai(input,"proposal-b","a","a");
        var plan=CandidateUnionShadow.plan(input,List.of(a,a,b),24);
        assertEquals(3,plan.entries().size()); assertEquals(1,plan.duplicateInputs());
        assertEquals(2,plan.entries().stream().filter(e -> e.candidate().origin()==CandidateUnionShadow.Origin.AI).count());
    }
    @Test void changedSnapshotCannotSilentlyReuseAiCandidates() {
        var old=new ReviewInput(List.of(row("a",0,"원문"))); var changed=new ReviewInput(List.of(row("a",0,"다른 원문")));
        assertThrows(IllegalArgumentException.class,()->CandidateUnionShadow.plan(changed,List.of(ai(old,"x","a","a")),1));
    }
    @Test void unknownEvidenceAndConflictingSourceFailClosed() {
        var input=new ReviewInput(List.of(row("a",0,"원문"),row("b",1000,"다음")));
        assertThrows(IllegalArgumentException.class,()->CandidateUnionShadow.plan(input,List.of(ai(input,"x","missing","missing")),1));
        assertThrows(IllegalArgumentException.class,()->CandidateUnionShadow.plan(input,List.of(ai(input,"x","a","a"),ai(input,"x","b","b")),2));
    }
    @Test void timeBucketsPreventEarlyClusterFromTakingAllSlots() {
        var input=new ReviewInput(List.of(row("a",0,"첫 대화"),row("b",1000,"다음 대화"),row("c",61000,"뒤쪽 대화")));
        var plan=CandidateUnionShadow.plan(input,List.of(ai(input,"1","a","a"),ai(input,"2","b","b"),ai(input,"3","c","c")),2);
        assertEquals(List.of("a","c"),plan.entries().stream().filter(e -> e.state()==CandidateUnionShadow.State.READY)
                .map(e -> e.candidate().anchorId()).toList());
        assertEquals(CandidateUnionShadow.State.BUDGET_SKIPPED,plan.entries().get(1).state());
    }
    @Test void zeroBudgetIsNotPassAndPlanIsRepeatable() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는")));
        var plan=CandidateUnionShadow.plan(input,List.of(),0);
        assertEquals(CandidateUnionShadow.State.BUDGET_SKIPPED,plan.entries().get(0).state());
        assertEquals(plan,CandidateUnionShadow.plan(input,List.of(),0));
    }
    @Test void incompatibleContextDoesNotConsumeValidCandidateBudget() {
        var input=new ReviewInput(List.of(row("a",0,"대화1"),row("b",20000,"대화2"),row("c",61000,"대화3")));
        var plan=CandidateUnionShadow.plan(input,List.of(ai(input,"bad","a","a","b"),ai(input,"good","c","c")),1);
        assertEquals(CandidateUnionShadow.State.CONTEXT_INVALID,plan.entries().get(0).state());
        assertEquals(CandidateUnionShadow.State.READY,plan.entries().get(1).state());
    }
    @Test void interveningSpeechIsPreservedNotTreatedAsSilence() {
        List<ReviewInput.Segment> rows=new ArrayList<>();
        for(int i=0;i<10;i++) rows.add(row("s"+i,i*1000,"원문"));
        var input=new ReviewInput(rows);
        var plan=CandidateUnionShadow.plan(input,List.of(ai(input,"x","s0","s0","s9")),1);
        assertEquals(CandidateUnionShadow.State.READY,plan.entries().get(0).state());
        assertEquals(10,plan.entries().get(0).raw().size());
    }
    @Test void bothOriginsReceiveSlotsWithinOneBusyMinute() {
        var input=new ReviewInput(List.of(row("a",0,"피부를 찢는"),row("b",1000,"살을 뜯는"),row("c",2000,"다른 발언")));
        var plan=CandidateUnionShadow.plan(input,List.of(ai(input,"x","c","c")),2);
        assertEquals(Set.of(CandidateUnionShadow.Origin.RULE,CandidateUnionShadow.Origin.AI),plan.entries().stream()
                .filter(e -> e.state()==CandidateUnionShadow.State.READY).map(e -> e.candidate().origin()).collect(java.util.stream.Collectors.toSet()));
    }
}
