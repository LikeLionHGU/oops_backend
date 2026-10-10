package com.example.oops.news;

import com.example.oops.domain.ReferenceSourceType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 검색 결과가 원출처에 얼마나 가까운지 규칙으로 가른다.
 *
 * **AI 로 분류하지 않는 이유:**
 * 검색 결과가 주장 하나당 6건이고 주장이 최대 6개다. 결과마다 AI 를 부르면
 * 영상 하나에 호출이 36번 더 나간다. 지금도 요청 한도에 걸리고 있어서
 * 그러면 정작 중요한 검증이 못 돈다.
 *
 * **한계를 분명히 해둔다:**
 * (2026-10) 웹 검색(Perplexity·Serper)으로 바꾸면서 공식 홈페이지·인터뷰 전문이 나오기 시작했다.
 * 대신 블로그·커뮤니티 글도 섞여 들어와서 USER_GENERATED_HOSTS 로 2차 자료로 내린다.
 * 키가 없으면 여전히 뉴스 RSS 뿐이라 인터뷰 전문이나 공식 홈페이지가 거의 안 나온다.
 * 그래서 실제로는 대부분 DIRECT_QUOTE_SOURCE 아니면 REPUTABLE_MEDIA 로 갈린다.
 * 당사자 자료를 제대로 찾으려면 일반 웹 검색이 필요하다.
 * 그건 새 API 키와 비용이 붙는 일이라 지금 범위 밖이다.
 *
 * 그래도 이 정도만으로 "당사자 말을 인용한 기사" 를 위로 올릴 수 있다.
 * 뮤직메카 인터뷰에서 확인된 실제 작업 방식이 그거였다.
 */
@Component
public class SourceClassifier {

    /** 공식 자료로 볼 도메인 */
    private static final List<String> OFFICIAL_HOSTS = List.of(
            ".go.kr", ".or.kr", ".gov", ".ac.kr", ".edu", ".int");

    /**
     * 누구나 쓸 수 있는 곳. 웹 검색으로 바꾸면서 생겼다. (2026-10 고도화)
     * 블로그·카페·커뮤니티·위키·영상 플랫폼은 그것만으로 사실의 근거가 되기 어렵다.
     * 2차 자료로 내려 두고, 대조 프롬프트도 이걸 근거로 단정하지 않게 막았다.
     */
    private static final List<String> USER_GENERATED_HOSTS = List.of(
            "blog.", "tistory.com", "cafe.", "post.naver.com", "brunch.co.kr", "namu.wiki", "wikipedia.org",
            "youtube.com", "youtu.be", "instagram.com", "facebook.com", "x.com", "twitter.com", "threads.net",
            "reddit.com", "dcinside.com", "fmkorea.com", "theqoo.net", "ruliweb.com", "clien.net", "inven.co.kr",
            "kin.naver.com", "velog.io", "medium.com");

    /** 기관·공공 성격의 매체명 */
    private static final List<String> OFFICIAL_NAMES = List.of(
            "정부", "보도자료", "공정거래위원회", "금융감독원", "통계청", "국세청", "대법원");

    /** 당사자 말을 그대로 옮긴 자료의 신호 */
    private static final List<String> QUOTE_SIGNALS = List.of(
            "인터뷰", "일문일답", "인터뷰 전문", "전문 공개", "간담회",
            "기자회견", "직접 밝혔", "이렇게 말했", "interview", "q&a", "in his own words");

    /** 본문에 실제 인용이 들어 있는지 */
    private static final List<String> QUOTE_MARKS = List.of(
            "\"", "“", "「", "'");

    private static final List<String> SAID_VERBS = List.of(
            "말했다", "밝혔다", "전했다", "설명했다", "회상했다", "said", "told", "recalled");

    /**
     * 자료 하나를 분류한다.
     *
     * 확신이 없으면 아래쪽(REPUTABLE_MEDIA)으로 둔다.
     * 잘못 올려서 "당사자 자료" 라고 표시하는 게 더 나쁘다.
     */
    /**
     * 호스트가 그 도메인이거나 하위 도메인인지. 점 경계까지 본다.
     * 그냥 endsWith 로 보면 "netflix.com" 이 "x.com" 으로 잡힌다.
     */
    static boolean matchesDomain(String host, String domain) {
        if (host == null || host.isEmpty() || domain == null || domain.isEmpty()) return false;
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        return host.equals(d) || host.endsWith("." + d);
    }

    public ReferenceSourceType classify(NewsSearchClient.NewsItem item) {
        if (item == null) {
            return ReferenceSourceType.SECONDARY_SOURCE;
        }
        String title = lower(item.title());
        String snippet = lower(item.description());
        String host = lower(hostOf(item.link()));
        String publisher = lower(item.publisher());

        if (USER_GENERATED_HOSTS.stream().anyMatch(h -> h.endsWith(".") ? host.startsWith(h) || host.contains("." + h) : matchesDomain(host, h))) {
            return ReferenceSourceType.SECONDARY_SOURCE;
        }

        if (OFFICIAL_HOSTS.stream().anyMatch(h -> matchesDomain(host, h))
                || OFFICIAL_NAMES.stream().anyMatch(publisher::contains)) {
            return ReferenceSourceType.OFFICIAL_SOURCE;
        }

        // 제목에 인터뷰 신호가 있고 본문에 실제 인용이 보이면 직접 인용으로 본다
        boolean interviewTitle = QUOTE_SIGNALS.stream().anyMatch(title::contains);
        boolean quotedBody = QUOTE_MARKS.stream().anyMatch(snippet::contains)
                && SAID_VERBS.stream().anyMatch(snippet::contains);

        if (interviewTitle || quotedBody) {
            return ReferenceSourceType.DIRECT_QUOTE_SOURCE;
        }

        // 매체명을 모르면 어디서 온 자료인지 알 수 없다
        if (publisher.isBlank() && host.isBlank()) {
            return ReferenceSourceType.SECONDARY_SOURCE;
        }
        return ReferenceSourceType.REPUTABLE_MEDIA;
    }

    private String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREAN);
    }
}
