package com.example.videoguard.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 구글 뉴스 RSS. API 키가 필요 없어서 기본 폴백으로 쓴다.
 *
 * 공개 RSS 피드라 별도 인증이 없고 비용도 들지 않는다.
 * 대신 네이버보다 국내 매체 커버리지가 얕고 스니펫이 짧다.
 */
@Slf4j
@Order(2)
@Component
public class GoogleNewsRssSearchClient implements NewsSearchClient {

    /** 최근 것만 본다. 오래된 기사는 "지금 민감한가" 판단에 방해가 된다. */
    private static final String RECENCY = " when:30d";

    private final RestClient restClient;

    public GoogleNewsRssSearchClient(@Qualifier("googleNewsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public boolean isEnabled() {
        return true;   // 키가 필요 없으므로 항상 사용 가능
    }

    @Override
    public String providerName() {
        return "구글뉴스RSS";
    }

    @Override
    public List<NewsItem> searchRecent(String query, int display) {
        try {
            // 직접 인코딩하면 안 된다. UriBuilder 가 한 번 더 인코딩해서
            // 검색어가 %25EC%25... 형태로 망가지고 결과가 항상 0건이 된다.
            String xml = restClient.get()
                    .uri(builder -> builder.path("/rss/search")
                            .queryParam("q", query + RECENCY)
                            .queryParam("hl", "ko")
                            .queryParam("gl", "KR")
                            .queryParam("ceid", "KR:ko")
                            .build())
                    .retrieve()
                    .body(String.class);

            if (xml == null || xml.isBlank()) {
                return List.of();
            }
            List<NewsItem> items = parse(xml, display);
            log.info("[news:google] '{}' → {}건", query, items.size());
            return items;

        } catch (RestClientException e) {
            log.warn("[news:google] 검색 실패 query={} : {}", query, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("[news:google] RSS 파싱 실패 query={} : {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<NewsItem> parse(String xml, int display) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 외부 엔티티 참조 차단 (XXE 방어)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        NodeList items = builder.parse(new InputSource(new StringReader(xml)))
                .getElementsByTagName("item");

        List<NewsItem> result = new ArrayList<>();
        for (int i = 0; i < items.getLength() && result.size() < display; i++) {
            Node node = items.item(i);
            if (!(node instanceof Element element)) continue;

            String title = text(element, "title");
            if (title.isBlank()) continue;

            result.add(new NewsItem(
                    title,
                    stripHtml(text(element, "description")),
                    text(element, "pubDate"),
                    text(element, "link")));
        }
        return result;
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        String value = nodes.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }

    /** description 안에 기사 링크 목록이 HTML 로 들어 있다. 텍스트만 남긴다. */
    private String stripHtml(String text) {
        if (text == null) return "";
        String stripped = text.replaceAll("<[^>]*>", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped.length() > 300 ? stripped.substring(0, 300) : stripped;
    }
}
