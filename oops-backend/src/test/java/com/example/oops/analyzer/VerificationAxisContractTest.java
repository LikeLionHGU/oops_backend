package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.*;

/** Diagnostic characterization, not synthetic controversy gold or a desired long-term contract. */
class VerificationAxisContractTest {
    @Test void discoveryAxisStillBlocksTargetlessSexualContentEvenWhenVerificationTreatsExpressionItself() {
        var f=new CandidateReviewEngineTest();String text="신체를 보여주겠다는 표현 자체를 이야기합니다.";
        var context=f.context(text);String id="stt-index-0";
        var assessment=new LlmDecision(id,"REVIEW_REQUIRED",text,
                "노골적인 신체 공개 표현을 소재로 삼는 내용 자체를 검토하는 합성 응답입니다.",
                "SEXUAL",null,.5,null,null,List.of(),List.of(new LlmEvidence(id,text,"PRIMARY")),
                null,null,null,"자발적 제안이라는 정상 해석과 표현 자체의 검토 사유를 구분하는 합성 응답입니다.");
        var quotes=List.of(new Quote(id,text));
        var target=new Candidate("c",new Proposal(id,"TARGET_TREATMENT","표현과 대상 확인",quotes),context.reviewInput().segments(),false,false);
        var expression=new Candidate("c",new Proposal(id,"EXPRESSION_CONTENT","표현과 대상 확인",quotes),context.reviewInput().segments(),false,false);
        assertThat(validate(target,assessment,context.reviewInput()).failureCode()).isEqualTo("TARGET_REQUIRED");
        assertThat(validate(expression,assessment,context.reviewInput()).failureCode()).isNull();
    }
    @Test void sourceContainmentCheckDoesNotEstablishThatAdverbIdentifiesAPerson() {
        var assessment=new LlmDecision("s","REVIEW_REQUIRED","살짝 보여드릴까요?","대상 관계 점검용 합성 응답입니다.",
                "SEXUAL","대화 상대인 형",.5,null,null,List.of(),List.of(
                        new LlmEvidence("s","살짝 보여드릴까요?","TARGET"),new LlmEvidence("c","형만","CONTEXT")),
                "PERSON","CONTEXTUAL","뒤의 형만이라는 말과 연결합니다.","별도 의미 검수가 필요합니다.","살짝");
        var inspected=inspectTarget(assessment);
        assertThat(inspected.failureDetail()).isNull();
        assertThat(assessment.targetMention()).isEqualTo("살짝");
        // Passing a substring/role contract is NOT evidence that this adverb refers to a person.
    }
}
