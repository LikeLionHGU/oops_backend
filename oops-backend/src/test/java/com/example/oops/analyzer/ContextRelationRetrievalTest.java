package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic role/search contracts only; no provider calls or semantic-accuracy claims. */
class ContextRelationRetrievalTest {
    private static final String SITUATION = "가게가 없는 상황 설명";
    private static final String EVALUATION = "선택지를 낮추는 평가 표현";
    private final ContextExampleSelectionTest fixture = new ContextExampleSelectionTest();

    private ContextRelationRetrieval.Profile profile(boolean reverse) {
        return new ContextRelationRetrieval.Profile("authored-relation-search-1", "AUTHORED_RETRIEVAL_NOT_SOURCE_EVIDENCE",
                "a".repeat(64), List.of("source-id"), reverse?EVALUATION:SITUATION, "",
                reverse?SITUATION:EVALUATION, "연결 검색 전용 설명");
    }
    private ContextRelationRetrieval.Query query() {
        return new ContextRelationRetrieval.Query(fixture.query(SITUATION),List.of(),fixture.query(EVALUATION),List.of());
    }
    private ContextRelationRetrieval.Query completeQuery() {
        return new ContextRelationRetrieval.Query(query().situation(),List.of(),query().evaluation(),
                fixture.query(SITUATION+" "+EVALUATION));
    }
    Archive archive() {
        var a = new ContextUnitRoutingTest().units();
        var examples = a.examples().stream().map(e -> new ContextExample(e.id(),e.familyId(),e.mechanismIds(),e.channels(),
                e.context(),e.retrievalView(),e.routingKeys(),profile(e.id().equals("source-0")))).toList();
        return new Archive("review-guidelines-7",a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),a.sourceCases(),a.guidelines(),a.contextDictionarySha256(),examples);
    }
    @Test void identicalWordBagsWithSwappedRolesReceiveDifferentBoundedScores() {
        double aligned = ContextRelationRetrieval.component(profile(false),completeQuery());
        double swapped = ContextRelationRetrieval.component(profile(true),completeQuery());
        assertThat(aligned).isGreaterThan(swapped).isBetween(0.0,.15);
        assertThat(ContextRelationRetrieval.component(null,completeQuery())).isZero();
        assertThat(ContextRelationRetrieval.component(profile(false),ContextRelationRetrieval.Query.empty())).isZero();
        assertThat(ContextRelationRetrieval.component(profile(false),query())).isZero(); // Linkage required.
        assertThat(ContextRelationRetrieval.valid(profile(false))).isTrue(); // Expression-only: target optional.
    }
    @Test void profileValidationRejectsInvalidSourceIdsTextAndFalseBasis() {
        var p=profile(false);
        for (var invalid:List.of(
                new ContextRelationRetrieval.Profile(p.version(),"SOURCE_EVIDENCE",p.sourceSha256(),p.sourceIds(),"","",EVALUATION,"link"),
                new ContextRelationRetrieval.Profile(p.version(),p.basis(),"bad",p.sourceIds(),"","",EVALUATION,"link"),
                new ContextRelationRetrieval.Profile(p.version(),p.basis(),p.sourceSha256(),List.of("a","a"),"","",EVALUATION,"link"),
                new ContextRelationRetrieval.Profile(p.version(),p.basis(),p.sourceSha256(),p.sourceIds(),"","","","link")))
            assertThat(ContextRelationRetrieval.valid(invalid)).isFalse();
    }
    @Test void relationBonusReranksWithoutKeysAndNeverLeaksSearchDescriptionsAsEvidence() {
        var raw=fixture.query("검색용으로 고정한 별도 문구");
        var library=fixture.library(archive(),1,3000,"");
        var result=library.select(TimelineEventType.SPEECH,raw,"q",raw,Set.of(),completeQuery());
        assertThat(result.trace().exampleIds()).containsExactly("source-1");
        assertThat(result.trace().relation().availableProfiles()).isEqualTo(2);
        assertThat(result.trace().relation().selectedScores().get(0).relationComponent()).isPositive();
        assertThat(result.trace().balance()).isNull(); // Candidate/context interleaving preserved without keys.
        assertThat(result.trace().retrievalMethod()).isEqualTo("V7_ROLE_ALIGNED_LEXICAL_NOT_SEMANTIC");
        assertThat(result.prompt()).doesNotContain("relationProfile","연결 검색 전용 설명","source-id",SITUATION,EVALUATION);
        assertThat(JSON.writeValueAsString(result.trace().relation())).doesNotContain(SITUATION,EVALUATION);
        assertThat(library.select(TimelineEventType.SPEECH,raw,"q",raw,Set.of(),completeQuery())).isEqualTo(result);
        assertThat(fixture.library(archive(),1,3000,"family-1")
                .select(TimelineEventType.SPEECH,raw,"q",raw,Set.of(),completeQuery()).trace().exampleIds()).containsExactly("source-0");
        assertThat(fixture.library(archive(),0,3000,"")
                .select(TimelineEventType.SPEECH,raw,"q",raw,Set.of(),completeQuery()).trace().exampleIds()).isEmpty();
        assertThat(fixture.library(archive(),2,256,"")
                .select(TimelineEventType.SPEECH,raw,"q",raw,Set.of(),completeQuery()).trace().exampleIds()).isEmpty();
        assertThat(library.select(TimelineEventType.SPEECH,fixture.query("서로 완전히 무관한 단어"),"q",List.of(),Set.of(),completeQuery())
                .trace().exampleIds()).isEmpty(); // Role comparison does not bypass lexical eligibility.
    }
    @Test void v6IsUnchangedAndCannotSmuggleProfilesThroughOldSchema() {
        var a = new ContextUnitRoutingTest().units(); var l=fixture.library(a,2,3000,"");
        var raw=fixture.query("검색용으로 고정한 별도 문구");
        var before=l.select(TimelineEventType.SPEECH,raw,"q",raw,Set.of("graphic-body-metaphor"));
        assertThat(l.select(TimelineEventType.SPEECH,raw,"q",raw,Set.of("graphic-body-metaphor"),completeQuery())).isEqualTo(before);
        assertThat(JSON.writeValueAsString(before.trace())).doesNotContain("relationComponent","\"relation\"");
        var node=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(archive());
        node.put("schemaVersion","review-guidelines-6");
        assertThat(fixture.library(JSON.treeToValue(node,Archive.class),2,3000,"").trace(TimelineEventType.SPEECH).state())
                .isEqualTo("INVALID_ARCHIVE");
    }
    @Test void relationSearchPreservesCandidateFirstSlotAgainstStrongerSurroundingLexicalMatch() {
        var a=archive(); var examples=new ArrayList<ContextExample>();
        for(var e:a.examples()) {
            String text=e.id().equals("source-0")?EVALUATION+" 가나다라 마바사아 자차카타 파하가나 다라마바 사아자차 카타파하":SITUATION;
            examples.add(new ContextExample(e.id(),e.familyId(),e.mechanismIds(),e.channels(),e.context(),
                    new RetrievalView("context-unit-retrieval-1","AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",text,retrievalHash(text)),
                    e.routingKeys(),profile(false)));
        }
        var custom=new Archive(a.schemaVersion(),a.version(),a.status(),false,a.usage(),a.sourceBundleSha256(),
                a.refreshOrDeleteBy(),a.sourceCases(),a.guidelines(),a.contextDictionarySha256(),examples);
        var raw=List.of(new ReviewInput.Segment("s",TimelineEventType.SPEECH,0,1000,SITUATION,null),
                new ReviewInput.Segment("e",TimelineEventType.SPEECH,1000,2000,EVALUATION,null));
        var rank=new ContextExampleSelector(examples,a.guidelines()).rank(TimelineEventType.SPEECH,raw,Set.of());
        assertThat(rank.get(0).example().id()).isEqualTo("source-1"); // Global lexical pool would displace the anchor.
        var library=fixture.library(custom,2,3000,"");
        var after=library.select(TimelineEventType.SPEECH,raw,"q",List.of(raw.get(1)),Set.of(),completeQuery());
        assertThat(after.trace().exampleIds()).containsExactly("source-0","source-1");
        assertThat(after.trace().relation()).isNotNull();
        assertThat(after.trace().balance()).isNull();
    }
    @Test void explicitRoutingBalanceIncludesRelationBonusWithoutChangingKeyWeights() {
        var raw=fixture.query("검색용으로 고정한 별도 문구");
        var result=fixture.library(archive(),1,3000,"").select(TimelineEventType.SPEECH,raw,"q",raw,
                Set.of("graphic-body-metaphor","local-life-devaluation"),completeQuery());
        assertThat(result.trace().exampleIds()).containsExactly("source-1");
        var choice=result.trace().balance().choices().get(0);
        assertThat(choice.hintBonus()).isEqualTo(.15);
        assertThat(choice.newKeyBonus()).isEqualTo(.10);
        assertThat(choice.relationComponent()).isPositive();
        assertThat(choice.selectionScore()).isEqualTo(choice.lexicalComponent()+choice.hintBonus()+choice.newKeyBonus()+choice.relationComponent());
    }
    @Test void roleQueryUsesOnlyExistingUnmodifiedRawAndRejectsMissingIds() {
        var raw=List.of(new ReviewInput.Segment("s",TimelineEventType.SPEECH,0,1000,"원문 방언 그대로",null),
                new ReviewInput.Segment("e",TimelineEventType.SPEECH,1000,2000,"평가 그대로",null));
        var roles=new SourceRelations(List.of("s"),List.of(),List.of(),List.of("e"),List.of("s"));
        var proposal=new Proposal("e","TARGET_TREATMENT",null,List.of(new Quote("e",null)),roles);
        var query=ContextRelationRetrieval.from(new Candidate("c",proposal,raw,false,false));
        assertThat(query.situation()).containsExactly(raw.get(0));
        assertThat(query.target()).containsExactly(raw.get(0));
        assertThat(query.evaluation()).containsExactly(raw.get(1));
        var bad=new Proposal("e","TARGET_TREATMENT",null,proposal.evidence(),
                new SourceRelations(List.of("invented"),List.of(),List.of(),List.of("e"),List.of()));
        assertThat(ContextRelationRetrieval.from(new Candidate("c",bad,raw,false,false)).present()).isFalse();
    }
    @Test void actualEngineUsesV7OverloadBeforeFreezingWithoutExtraModelCalls() {
        var library=spy(fixture.library(archive(),2,3000,""));
        var f=new CandidateReviewEngineTest(); var context=f.context(SITUATION,EVALUATION);
        var ids=List.of("stt-index-0","stt-index-1");
        var roles=new SourceRelations(List.of(ids.get(0)),List.of(),List.of(),List.of(ids.get(1)),List.of());
        f.discovery(ids,new Proposal(ids.get(1),"TARGET_TREATMENT",null,
                ids.stream().map(id -> new Quote(id,null)).toList(),roles));
        f.verification(new Verification("candidate-1",f.linkedPass(ids.get(1),EVALUATION,ids.get(0),SITUATION)));
        var result=run(f.client,context,24,null,null,library,null,0,true);
        var rolesCaptor=ArgumentCaptor.forClass(ContextRelationRetrieval.Query.class);
        verify(library).select(eq(TimelineEventType.SPEECH),anyCollection(),startsWith("unit-"),anyCollection(),anySet(),rolesCaptor.capture());
        assertThat(rolesCaptor.getValue().present()).isTrue();
        assertThat(rolesCaptor.getValue().evaluation().get(0).text()).isEqualTo(EVALUATION);
        verify(f.client).completeAsJson(anyString(),anyString(),eq(Discovery.class));
        verify(f.client).completeAsJson(anyString(),anyString(),eq(VerificationResult.class));
        verify(f.client).failureCount();
        verifyNoMoreInteractions(f.client);
        assertThat(result.findings()).isEmpty(); // Retrieval never forces a warning.
    }
}
