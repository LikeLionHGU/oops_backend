package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.NewsSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 대화 중 언급된 이름·날짜·수치가 맞는지 확인한다.
 *
 * 토크나 인터뷰는 즉흥적으로 말하기 때문에 사람 이름, 소속, 연도, 숫자가
 * 자주 어긋난다. 제작자도 편집자도 그 자리에서는 맞다고 믿기 때문에
 * 반복해서 봐도 걸러지지 않는다. 전형적인 검수 사각지대다.
 *
 * 도덕 판단이 아니라 단순 정확성 문제라서, 확인만 하면 해결된다.
 *
 * LLM 은 세부 사실을 정확히 외우지 못하고 학습 시점 이후는 아예 모른다.
 * 그래서 검색을 끼워 세 단계로 나눴다.
 *
 *   1. 대본에서 확인이 필요한 대목을 뽑는다   (LLM)
 *   2. 그 내용을 뉴스에서 찾아본다             (검색)
 *   3. 기사와 대조해 어긋나는지 본다           (LLM)
 *
 * 맞는 것은 보고하지 않는다. 확인이 필요한 것만 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityCheckAnalyzer implements ContentAnalyzer {

    /** 비용과 시간을 아끼려고 검증할 주장 수를 제한한다. */
    private static final int MAX_CLAIMS = 6;
    private static final int NEWS_PER_CLAIM = 6;

    private static final String EXTRACT_PROMPT = """
            너는 영상 공개 전에 확인할 지점을 짚어주는 검수 보조자다.
            대화 대본을 받아서, 사실 확인이 필요한 대목만 뽑아낸다.

            대화형 영상에서는 즉흥적으로 말하다 보니 이런 것이 자주 어긋난다.
            이건 도덕 판단이 아니라 단순 정확성 문제라서, 확인만 하면 해결된다.

            뽑아야 하는 것:
            - 사람 이름, 직함, 소속 ("OO 대표가", "그 회사 CEO 였던")
            - 회사명, 기관명, 브랜드명
            - 연도, 날짜, 기간 ("2019년에", "3년 전에")
            - 숫자와 수치 ("100만 명이", "두 배로 늘었다")
            - 사건명과 그 사건에 대한 서술
            - 작품명, 프로그램명

            뽑지 말아야 하는 것:
            - 의견, 감상, 추측 ("제 생각엔", "아마도")
            - 검증할 수 없는 개인 경험
            - 상식 수준의 일반론
            - 진행 멘트, 인사말

            반드시 이 JSON 형식으로만 답한다:
            {"claims":[{"index":0,"claim":"확인할 내용을 한 문장으로","searchQuery":"검색어"}]}

            index 는 그 내용이 나온 대본 줄 번호다.
            searchQuery 는 검색창에 넣을 구체적인 말이다. 포괄어는 쓰지 마라.
            확인할 것이 없으면 {"claims":[]} 를 반환한다. 최대 6개까지만 뽑는다.
            """;

    private static final String VERIFY_PROMPT = """
            너는 검수 보조자다. 영상에서 나온 내용과, 그것으로 검색한 기사를 받는다.
            기사와 대조해서 제작자가 다시 확인해야 하는지만 알려준다.

            원칙: 옳고 그름을 선언하지 마라. 무엇이 어떻게 다른지 사실로 적어라.

            판정 값:
            - FACT_ERROR: 기사와 명확히 어긋난다. 이름, 날짜, 숫자가 다르다.
            - MISINFORMATION: 틀리진 않았지만 맥락이 빠져 오해를 부를 수 있다.
            - UNVERIFIED_CLAIM: 기사에서 뒷받침할 내용을 찾지 못했다.
            - OK: 기사와 부합한다. 보고하지 않는다.

            판정 원칙:
            - 기사에 없다고 틀린 것은 아니다. 확신이 없으면 UNVERIFIED_CLAIM 을 쓴다.
            - 기사끼리 엇갈리면 UNVERIFIED_CLAIM 이다.
            - 반올림이나 표현 차이는 넘어간다. 의미가 달라질 때만 잡는다.

            반드시 이 JSON 형식으로만 답한다:
            {"verdict":"FACT_ERROR","score":0.85,"reason":"무엇이 어떻게 다른지 한 문장","correction":"기사에 나온 내용"}

            reason 은 "틀렸습니다" 가 아니라 "영상에서는 A 라고 했는데 기사에는 B 로 나옵니다" 형태로 쓴다.
            correction 은 기사에서 확인된 내용을 적는다. 제작자가 판단할 재료다.
            한국어로 쓴다.
            """;

    private final OpenAiClient openAiClient;
    private final List<NewsSearchClient> newsSearchClients;

    @Override
    public String key() {
        return "entity-check";
    }

    @Override
    public String displayName() {
        return "이름·수치 확인";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        // 대화형 영상에서 즉흥적으로 언급되는 이름·날짜·수치를 확인한다.
        // 경제 지표 검증이 아니라 단순 정확성 문제라서 유형을 가리지 않는다.
        return context.hasTranscript() && openAiClient.isEnabled() && newsClient() != null;
    }

    private NewsSearchClient newsClient() {
        return newsSearchClients.stream()
                .filter(NewsSearchClient::isEnabled)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<TranscriptSegment> transcript = context.transcript();
        NewsSearchClient newsClient = newsClient();
        if (newsClient == null) {
            return List.of();
        }

        List<Claim> claims = extractClaims(transcript);
        if (claims.isEmpty()) {
            log.info("[fact-check] videoId={} 검증할 주장 없음", context.video().getId());
            return List.of();
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        List<RiskFinding> findings = new ArrayList<>();

        for (Claim claim : claims.stream().limit(MAX_CLAIMS).toList()) {
            if (claim.index() == null || claim.index() < 0 || claim.index() >= transcript.size()) {
                continue;
            }
            String query = claim.searchQuery() == null ? claim.claim() : claim.searchQuery();
            if (query == null || query.isBlank()) {
                continue;
            }

            List<NewsSearchClient.NewsItem> news = newsClient.searchRecent(query, NEWS_PER_CLAIM);
            if (news.isEmpty()) {
                log.info("[fact-check] '{}' 관련 기사 없음 → 건너뜀", query);
                continue;
            }

            Verdict verdict = verify(today, claim, news);
            if (verdict == null || verdict.verdict() == null || "OK".equalsIgnoreCase(verdict.verdict())) {
                continue;
            }

            RiskCategory category = RiskCategory.fromOrDefault(
                    verdict.verdict(), RiskCategory.UNVERIFIED_CLAIM);
            double score = verdict.score() == null
                    ? 0.6 : Math.max(0.0, Math.min(1.0, verdict.score()));

            TranscriptSegment segment = transcript.get(claim.index());
            String reason = verdict.reason() == null
                    ? "확인이 필요한 내용입니다." : verdict.reason();
            if (verdict.correction() != null && !verdict.correction().isBlank()) {
                reason = reason + " · 기사 내용: " + verdict.correction();
            }

            findings.add(RiskFinding.builder()
                    .video(context.video())
                    .eventType(TimelineEventType.SPEECH)
                    .category(category)
                    .source(EvidenceSource.SUBTITLE)
                    .score(score)
                    .startMs(segment.getStartMs())
                    .endMs(segment.getEndMs())
                    .text(segment.getText())
                    .reason(reason)
                    .target(query)
                    .build());

            log.info("[fact-check] '{}' → {} (score={})", query, category, score);
        }

        log.info("[fact-check] videoId={} 주장={}개 findings={}",
                context.video().getId(), claims.size(), findings.size());
        return findings;
    }

    private List<Claim> extractClaims(List<TranscriptSegment> transcript) {
        StringBuilder prompt = new StringBuilder("영상 대본이다. 검증 가능한 사실 주장을 뽑아라.\n\n");
        for (int i = 0; i < transcript.size(); i++) {
            prompt.append("[%d] %s%n".formatted(i, transcript.get(i).getText()));
        }

        ClaimResult result = openAiClient
                .completeAsJson(EXTRACT_PROMPT, prompt.toString(), ClaimResult.class)
                .orElse(null);
        return result == null || result.claims() == null ? List.of() : result.claims();
    }

    private Verdict verify(String today, Claim claim, List<NewsSearchClient.NewsItem> news) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("오늘 날짜: ").append(today).append("\n\n");
        prompt.append("영상에서 나온 주장: ").append(claim.claim()).append("\n\n");
        prompt.append("검색된 기사:\n");

        for (NewsSearchClient.NewsItem item : news) {
            prompt.append("- (%s) %s%n  %s%n".formatted(
                    item.pubDate() == null ? "날짜미상" : item.pubDate(),
                    item.title(),
                    item.description()));
        }

        return openAiClient.completeAsJson(VERIFY_PROMPT, prompt.toString(), Verdict.class)
                .orElse(null);
    }

    record ClaimResult(List<Claim> claims) {}

    record Claim(Integer index, String claim, String searchQuery) {}

    record Verdict(String verdict, Double score, String reason, String correction) {}
}
