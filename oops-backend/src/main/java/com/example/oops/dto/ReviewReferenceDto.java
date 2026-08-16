package com.example.oops.dto;

import com.example.oops.domain.ReviewReference;

/**
 * 검토 후보에 붙는 참고 자료. AI 가 실제로 본 기사다.
 *
 * 사용자가 직접 열어서 확인하는 것이 목적이므로 url 은 항상 채워 보낸다.
 * (명세에는 없는 추가 필드다. 안 써도 무방하지만, 이게 없으면
 *  사용자는 AI 설명을 검증할 방법이 없다)
 */
public record ReviewReferenceDto(
        String title,
        String publisher,
        String url,
        String publishedAt,
        String snippet
) {
    public static ReviewReferenceDto from(ReviewReference r) {
        return new ReviewReferenceDto(
                r.getTitle(),
                r.getPublisher(),
                r.getUrl(),
                r.getPublishedAt(),
                r.getSnippet());
    }
}
