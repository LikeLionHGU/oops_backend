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
 * 경제·정책·금융 영상의 사실 주장을 검증한다.
 *
 * 이 유형에서는 틀린 숫자 하나가 곧 논란이 된다.
 * "실업률이 5%를 넘었다", "정부가 세금을 두 배 올렸다" 같은 말은
 * 맞으면 아무 문제가 없고 틀리면 영상 전체의 신뢰가 무너진다.
 * 표현이 거칠어서 문제가 되는 다른 유형과는 성격이 완전히 다르다.
 *
 * LLM 은 학습 시점 이후의 수치를 모르고, 애초에 통계를 정확히 외우지 못한다.
 * 그래서 세 단계로 나눴다.
 *
 *   1. 대본에서 검증 가능한 주장을 뽑는다                 (LLM)
 *   2. 각 주장을 뉴스에서 찾아본다                        (검색)
 *   3. 기사와 대조해 맞는지, 틀린지, 확인 불가인지 가른다   (LLM)
 *
 * 확인된 것은 보고하지 않는다. 문제가 있는 것만 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactCheckAnalyzer implements ContentAnalyzer {

    /** 비용과 시간을 아끼려고 검증할 주장 수를 제한한다. */
    private static final int MAX_CLAIMS = 6;
    private static final int NEWS_PER_CLAIM = 6;

    private static final String EXTRACT_PROMPT = """
            너는 경제·정책·금융 영상을 검수하는 팩트체커다.
            대본을 받아서 "사실인지 따져볼 수 있는 주장" 만 뽑아낸다.

            뽑아야 하는 것:
            - 구체적인 수치나 통계 ("실업률이 4%를 넘었다", "작년보다 30% 올랐다")
            - 정책·제도에 대한 단정 ("정부가 이 법을 폐지했다")
            - 사건에 대한 서술 ("그 회사가 상장폐지됐다")
            - 인과관계 단정 ("금리를 올려서 물가가 잡혔다")
            - 특정 기업·인물에 대한 사실 주장

            뽑지 말아야 하는 것:
            - 의견, 전망, 추측 ("제 생각엔", "아마 오를 겁니다")
            - 검증할 수 없는 개인 경험
            - 상식 수준의 일반론
            - 진행 멘트, 인사말

            반드시 이 JSON 형식으로만 답한다:
            {"claims":[{"index":0,"claim":"주장을 한 문장으로 정리","searchQuery":"뉴스 검색어"}]}

            index 는 그 주장이 나온 대본 줄 번호다.
            searchQuery 는 뉴스 검색창에 넣을 구체적인 검색어다. 포괄어는 쓰지 마라.
            검증할 주장이 없으면 {"claims":[]} 를 반환한다. 최대 6개까지만 뽑는다.
            """;

    private static final String VERIFY_PROMPT = """
            너는 팩트체커다. 영상에서 나온 주장과, 그 주장으로 검색한 뉴스 기사를 받는다.
            기사를 근거로 주장이 맞는지 판정한다.

            판정 값:
            - FACT_ERROR: 기사와 명백히 어긋난다. 숫자나 사실이 틀렸다.
            - MISINFORMATION: 완전히 틀리진 않았지만 맥락을 빼거나 과장해서 오해를 부른다.
            - UNVERIFIED_CLAIM: 기사에서 근거를 찾을 수 없다. 단정적으로 말하기엔 위험하다.
            - OVERCONFIDENT_FORECAST: 불확실한 미래를 확정된 것처럼 말한다.
            - OK: 기사와 부합한다. 문제없다.

            판정 원칙:
            - 기사에 없다고 무조건 틀린 것은 아니다. 확신이 없으면 UNVERIFIED_CLAIM 을 쓴다.
            - 기사끼리 엇갈리면 UNVERIFIED_CLAIM 이다.
            - 반올림이나 표현 차이는 문제 삼지 않는다. 의미가 달라질 때만 잡는다.
            - OK 면 점수는 무시된다.

            반드시 이 JSON 형식으로만 답한다:
            {"verdict":"FACT_ERROR","score":0.85,"reason":"무엇이 어떻게 다른지 두 문장 이내","correction":"기사에 따르면 실제로는 어떤지"}

            reason 과 correction 은 한국어로 쓴다. OK 면 나머지는 비워도 된다.
            """;

    private final OpenAiClient openAiClient;
    private final List<NewsSearchClient> newsSearchClients;

    @Override
    public String key() {
        return "fact-check";
    }

    @Override
    public String displayName() {
        return "사실 검증";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        // 경제·정책·투자 영상에서만 돌린다. 브이로그에 팩트체크는 의미가 없다.
        if (!context.genreOrGeneral().needsFactCheck()) {
            return false;
        }
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
            String reason = verdict.reason() == null ? "사실 확인이 필요합니다." : verdict.reason();
            if (verdict.correction() != null && !verdict.correction().isBlank()) {
                reason = reason + " (확인된 내용: " + verdict.correction() + ")";
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
