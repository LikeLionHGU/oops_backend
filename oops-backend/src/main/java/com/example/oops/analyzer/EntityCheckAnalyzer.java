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
            - **지금 있는 자리나 눈앞의 상황에 대한 말**
              "여기 롯데리아 없나?", "이 가게 문 닫았네" 같은 것.
              화자가 그 자리에서 보고 하는 말이라 기사로 확인할 수 없다.
            - **채널이나 출연자 자신에 대한 정보**
              구독자 수, 조회수, 채널 이력 같은 것.
              기사에 나올 리 없고 나와도 시점이 다르다.
            - 농담이나 과장이 분명한 수치 ("백만 번은 말했다")

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
            - **기사가 다른 사안을 다루고 있으면 OK 를 반환해라.**
              검색어가 같아도 내용이 무관하면 대조할 수 없다.
              예: 영상에서 "여기 롯데리아 없나?" 라고 했는데
              기사가 "롯데리아 싱가포르 2호점 오픈" 이면 서로 무관하다.
              이런 경우 절대 FACT_ERROR 로 판정하지 마라.
            - 기사에 없다고 틀린 것은 아니다.
              뒷받침할 내용이 없으면서 영상에서 단정적으로 말했을 때만
              UNVERIFIED_CLAIM 을 쓴다. 그냥 안 나온다고 쓰지 마라.
            - 기사끼리 엇갈리면 UNVERIFIED_CLAIM 이다.
            - 반올림이나 표현 차이는 넘어간다. 의미가 달라질 때만 잡는다.
            - 애매하면 OK 를 골라라. 이 유형은 잘못 잡으면 신뢰를 크게 잃는다.
              "틀렸다" 고 했는데 틀리지 않았으면 제작자가 도구 자체를 안 믿게 된다.

            반드시 이 JSON 형식으로만 답한다:
            {"verdict":"FACT_ERROR","score":0.85,"reason":"무엇이 어떻게 다른지 한 문장","correction":"기사에 나온 내용","sources":[0,2]}

            reason 은 "틀렸습니다" 가 아니라 "영상에서는 A 라고 했는데 기사에는 B 로 나옵니다" 형태로 쓴다.
            correction 은 기사에서 확인된 내용을 적는다. 제작자가 판단할 재료다.

            sources 는 **네 판단의 근거가 된 기사 번호**다.
            제작자가 직접 열어서 확인할 자료이므로 반드시 채워라.
            - 실제로 대조에 쓴 기사만 넣는다. 관련 없는 기사는 넣지 마라.
            - 판단에 쓴 기사가 여럿이면 여러 개를 넣는다. 최대 3개.
            - 뒷받침할 기사를 못 찾아 UNVERIFIED_CLAIM 으로 판정했다면 빈 배열로 둔다.

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

            // "기사에서 확인된 내용은 없습니다" 같은 응답은 아무 도움이 안 된다.
            // 근거가 없으면 올리지 않는다.
            String correction = verdict.correction();
            boolean hasEvidence = correction != null && !correction.isBlank()
                    && !correction.contains("없습니다") && !correction.contains("없음");

            if (category == RiskCategory.UNVERIFIED_CLAIM && !hasEvidence) {
                log.info("[entity-check] '{}' 근거가 없어 건너뜁니다", query);
                continue;
            }
            if (hasEvidence) {
                reason = reason + " · 기사 내용: " + correction;
            }

            RiskFinding finding = RiskFinding.builder()
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
                    .build();

            // AI 가 대조에 쓴 기사를 그대로 남긴다.
            // 무관한 기사와 비교한 오탐이라면 사용자가 링크를 열어보고 바로 판단할 수 있다.
            finding.adoptReferences(NewsReferenceSupport.pick(news, verdict.sources()));
            findings.add(finding);

            log.info("[fact-check] '{}' → {} (score={}, 참고자료 {}건)",
                    query, category, score, finding.getReferences().size());
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
        prompt.append(NewsReferenceSupport.format(news));

        return openAiClient.completeAsJson(VERIFY_PROMPT, prompt.toString(), Verdict.class)
                .orElse(null);
    }

    record ClaimResult(List<Claim> claims) {}

    record Claim(Integer index, String claim, String searchQuery) {}

    /** sources 는 판단 근거가 된 기사 번호. 참고 자료로 저장한다. */
    record Verdict(String verdict, Double score, String reason,
                   String correction, List<Integer> sources) {}
}
