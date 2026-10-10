package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 웹 검색 API 설정. (2026-10 고도화)
 *
 * 사실 확인과 최근 이슈 확인에 쓰는 검색을 뉴스 RSS 에서 웹 검색으로 바꿨다.
 * 뉴스만 보면 공식 자료·인터뷰 전문·기관 자료가 거의 안 나와서
 * "자료에서 확인되지 않음" 으로 끝나는 일이 많았다.
 *
 *   perplexity  메인. 웹 전체에서 찾고 페이지 본문 일부를 준다. 1,000건당 $5 (fast 는 $1).
 *   serper      보조. 구글 검색 결과. 메인이 결과를 충분히 못 찾으면 이어서 찾는다. 1,000건당 약 $1.
 *
 * 둘 다 키가 없으면 예전처럼 네이버 뉴스 → 구글 뉴스 RSS 를 쓴다.
 * 키는 application-secret.yml 에 넣는다 (커밋되지 않는다).
 *
 * @param maxSnippetChars 자료 하나당 AI 에게 넘길 최대 글자 수. 웹 페이지 본문이 길어서
 *                        자르지 않으면 대조 프롬프트 입력이 몇 배로 커진다. 기본 400.
 */
@ConfigurationProperties(prefix = "oops.search")
public record SearchApiProperties(Perplexity perplexity, Serper serper, Integer maxSnippetChars) {

    /**
     * @param searchType       web(기본, 더 넓게 찾음) 또는 fast(빠르고 싸지만 자료가 얇음)
     * @param recentSearchType 최근 이슈 확인용. "지금 진행 중인 일인가" 만 보면 돼서 fast 로 충분하다
     * @param country          ISO 국가 코드. 기본 KR
     * @param languages        결과 언어 제한. 비우면 제한 없음 (해외 인물·사건 자료도 받는다)
     * @param maxTokensPerPage 페이지 하나에서 뽑아 올 본문 토큰. 작을수록 싸고 빠르다. 기본 256
     */
    public record Perplexity(String apiKey, String searchType, String recentSearchType,
                             String country, List<String> languages, Integer maxTokensPerPage) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String searchTypeOrDefault() {
            return searchType == null || searchType.isBlank() ? "web" : searchType;
        }

        public String recentSearchTypeOrDefault() {
            return recentSearchType == null || recentSearchType.isBlank() ? "fast" : recentSearchType;
        }

        public String countryOrDefault() {
            return country == null || country.isBlank() ? "KR" : country;
        }

        public int maxTokensPerPageOrDefault() {
            return maxTokensPerPage == null || maxTokensPerPage <= 0 ? 256 : maxTokensPerPage;
        }
    }

    /**
     * @param gl 검색 국가 (기본 kr)
     * @param hl 검색 언어 (기본 ko)
     */
    public record Serper(String apiKey, String gl, String hl) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String glOrDefault() {
            return gl == null || gl.isBlank() ? "kr" : gl;
        }

        public String hlOrDefault() {
            return hl == null || hl.isBlank() ? "ko" : hl;
        }
    }

    public Perplexity perplexityOrEmpty() {
        return perplexity != null ? perplexity : new Perplexity(null, null, null, null, null, null);
    }

    public Serper serperOrEmpty() {
        return serper != null ? serper : new Serper(null, null, null);
    }

    public int maxSnippetCharsOrDefault() {
        return maxSnippetChars == null || maxSnippetChars <= 0 ? 400 : maxSnippetChars;
    }
}
