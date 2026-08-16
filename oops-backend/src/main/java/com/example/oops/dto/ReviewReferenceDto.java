package com.example.oops.dto;

import com.example.oops.domain.ReviewReference;

/**
 * 검토 후보에 붙는 참고 자료. 명세 §15-2 CandidateReference.
 *
 * AI 가 실제로 본 자료다. 사용자가 직접 열어서 확인하는 것이 목적이므로
 * url 은 항상 채워 보낸다.
 *
 * 명세 원칙: reason 문자열에 URL 을 합쳐 넣지 않고 여기에 구조화해서 준다.
 */
public record ReviewReferenceDto(
        String title,

        /** 매체·기관명. 없으면 주소의 호스트를 대신 넣는다 */
        String provider,

        String url,

        /** 게시 일자. 검색 결과가 주는 형식 그대로 */
        String publishedAt,

        /** 이 자료에서 확인된 내용. 왜 이 자료를 붙였는지에 해당한다 */
        String relevantContext,

        /** 기사 본문 발췌 */
        String snippet
) {
    public static ReviewReferenceDto from(ReviewReference r) {
        return new ReviewReferenceDto(
                r.getTitle(),
                r.getPublisher(),
                r.getUrl(),
                r.getPublishedAt(),
                r.getRelevantContext(),
                r.getSnippet());
    }
}
