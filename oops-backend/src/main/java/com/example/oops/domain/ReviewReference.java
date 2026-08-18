package com.example.oops.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.net.URI;

/**
 * 검토 후보 하나에 붙는 참고 자료 1건.
 *
 * 이걸 만든 이유:
 *   전에는 뉴스를 검색해서 LLM 에게 넣고 그대로 버렸다.
 *   사용자에게는 "기사에는 2020년으로 나옵니다" 라는 문장 하나만 갔다.
 *   어느 기사인지 확인할 방법이 없으니 결국 AI 말을 믿으라는 것과 같았다.
 *
 *   이 도구는 오탐이 반드시 난다. "롯데리아 없나?" 를
 *   "롯데리아 싱가포르 2호점 오픈" 기사와 대조한 적이 있다.
 *   링크가 있었다면 30초 만에 무관한 기사라고 넘겼을 것이다.
 *   지금은 판단할 방법이 없어서 도구 전체를 의심하게 된다.
 *
 * 그래서 AI 가 본 자료를 그대로 남긴다. 판단은 사람이 한다.
 */
@Getter
@Entity
@Table(name = "review_reference",
        indexes = @Index(name = "idx_reference_finding", columnList = "finding_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewReference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id")
    private RiskFinding finding;

    /** 기사 제목 */
    @Column(length = 500)
    private String title;

    /** 매체명. 없으면 주소에서 뽑아 채운다. */
    @Column(length = 100)
    private String publisher;

    /** 원문 주소 */
    @Column(length = 1000)
    private String url;

    /** 게시 일자. 검색 결과가 주는 형식 그대로 둔다. */
    @Column(length = 80)
    private String publishedAt;

    /** 기사 본문 발췌 */
    @Column(length = 500)
    private String snippet;

    /**
     * 이 자료에서 확인된 내용.
     *
     * snippet 은 기사에 있는 문장 그대로이고, 이건 "그래서 뭐가 확인됐는지" 다.
     * AI 가 대조 결과로 내놓은 문장을 넣는다. 카드에서 자료를 클릭할지 말지 판단하는 재료다.
     */
    @Column(length = 500)
    private String relevantContext;

    /**
     * 원출처에 얼마나 가까운 자료인지.
     *
     * "본인이 뭐라고 했는가" 를 확인할 때는 요약 기사보다
     * 본인 말을 인용한 기사가 낫다. 그걸 위로 올리기 위한 값이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(40)")
    private ReferenceSourceType sourceType;

    /** 표시 순서 */
    @Column(nullable = false)
    private int displayOrder;

    private ReviewReference(String title, String publisher, String url,
                            String publishedAt, String snippet) {
        this.title = trim(title, 500);
        this.publisher = trim(publisher, 100);
        this.url = trim(url, 1000);
        this.publishedAt = trim(publishedAt, 80);
        this.snippet = trim(snippet, 500);
    }

    /**
     * 검색 결과 1건을 참고 자료로 만든다.
     * 매체명이 없으면 주소의 호스트를 대신 쓴다. 아무것도 없는 것보다는 낫다.
     */
    public static ReviewReference of(String title, String publisher, String url,
                                     String publishedAt, String snippet) {
        String resolved = (publisher == null || publisher.isBlank())
                ? hostOf(url) : publisher;
        return new ReviewReference(title, resolved, url, publishedAt, snippet);
    }

    /**
     * 이 자료에서 확인된 내용을 채운다.
     *
     * 분석기가 자료를 다 붙인 뒤 한 번에 넣는다.
     * 어느 기사에서 나왔는지까지 쪼개려면 LLM 응답 형식을 더 복잡하게 만들어야 하는데,
     * 지금은 "이 자료들에서 확인된 내용" 수준이면 충분하다.
     */
    public void describeRelevance(String relevantContext) {
        this.relevantContext = trim(relevantContext, 500);
    }

    public void classifyAs(ReferenceSourceType sourceType) {
        this.sourceType = sourceType;
    }

    /** RiskFinding.addReference 에서만 호출한다. */
    void assignTo(RiskFinding finding, int displayOrder) {
        this.finding = finding;
        this.displayOrder = displayOrder;
    }

    /** 같은 기사를 두 번 붙이지 않기 위한 키 */
    public String dedupeKey() {
        if (url != null && !url.isBlank()) return url;
        return title == null ? "" : title;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
