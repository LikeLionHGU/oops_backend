package com.example.oops.analyzer;

import static com.example.oops.analyzer.TextBoundaryTrial.check;

/** Two known output-writing edits only; never edits the policy, references or input. */
final class DirectTargetWritingComparison {
    static final String VERSION="direct-target-writing-comparison-20261017-1";
    static final String CONSENT="NEW_USD050_DIRECT_TARGET_WRITING_TEXT_EGRESS_APPROVED";
    static final String SOURCE_SHA="14bd1e4f14db695d5b7dfc25066eeeaa8c3c47786b89c07ab6975ac807ae8a56";
    static final String OLD_NAME="  target은 문맥상 해석한 대상, targetMention은 TARGET 인용에 그대로 있는 지칭어(각 200자 이내)다.\n";
    static final String NEW_NAME="  targetMention은 TARGET 인용에 그대로 있는 지칭어, target은 아래 관계에 맞춘 대상명(각 200자 이내)다.\n";
    static final String OLD_ORDER="  다음에 해석한 대상 target을 적는다. 해석한 대상 이름을 원문 인용으로 바꾸거나 역으로 꾸미지 않는다.\n";
    static final String NEW_ORDER="""
              직접 지칭이면 target에도 같은 원문 지칭어를 적고, 대상 풀이는 targetReason에 둔다.
              예: 원문의 '당신'이 요청받는 상대라면 target='당신', targetMention='당신', EXPLICIT이다.
              '질문을 받은 상대'라는 풀이는 targetReason에 적는다. 이 재표현만으로 CONTEXTUAL을 만들지 않는다.
              다른 발언을 연결해야 대상을 식별할 때만 그 해석 대상명을 target에 적고 CONTEXTUAL 근거를 반환한다.
              '살짝' 같은 부사·행동어를 사람 지칭어로 쓰거나, 다른 사람을 같은 대상으로 바꾸지 않는다.
              계약을 맞추려고 판단·원문 인용·실제 대상 관계를 바꾸지 않는다.
            """;
    private DirectTargetWritingComparison() {}
    static String comparison(String baseline) {
        int end=baseline.indexOf("# 대상 필드 형식 예시");
        check(end>=0 && baseline.startsWith(CandidateReviewPrompts.POLICY),"Known verification instructions required");
        String body=baseline.substring(0,end),suffix=baseline.substring(end);
        body=VerificationContractComparison.replaceOnce(body,OLD_NAME,NEW_NAME);
        body=VerificationContractComparison.replaceOnce(body,OLD_ORDER,NEW_ORDER);
        return body+suffix;
    }
}
