package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.NewsSearchClient;
import com.example.oops.news.SourceClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 검증에 실제로 넘길 자료 수. 많이 넣으면 토큰만 늘고 판단은 흐려진다 */
    private static final int TOP_EVIDENCE = 4;

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
            {"claims":[{"index":0,"claim":"확인할 내용을 한 문장으로","subject":"누구·무엇에 대한 이야기인지",
                        "claimType":"DATE","searchQueries":["검색어1","검색어2"]}]}

            index 는 그 내용이 나온 대본 줄 번호다.

            claimType 은 다음 중 하나다. **이 값에 따라 어떤 자료를 먼저 볼지가 달라진다.**
            - PERSONAL_STATEMENT : 본인의 생각·의도·경험. "그때 이런 마음이었다고 했다"
            - DATE   : 연도·날짜·기간
            - NUMBER : 수치·통계·금액
            - ENTITY : 인물·회사·기관·작품의 이름이나 관계
            - EVENT  : 사건과 그에 대한 서술
            - GENERAL_FACT : 그 밖의 확인 가능한 사실

            subject 는 그 주장이 누구·무엇에 대한 것인지다. 사람 이름이나 회사명을 적는다.

            searchQueries 는 1~3개를 서로 다르게 만든다.
            - **PERSONAL_STATEMENT 라면 첫 번째를 당사자 발언을 찾는 검색어로 만들어라.**
              예: "OO 인터뷰 앨범 제작 의도", "OO 이 말한 OO"
              그래야 요약 기사가 아니라 본인이 직접 한 말을 찾을 수 있다.
            - 숫자·날짜라면 공식 발표나 통계를 찾는 검색어를 넣어라.
            - 포괄어는 쓰지 마라. 검색창에 그대로 넣을 수 있는 말이어야 한다.

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

            각 자료에는 유형이 붙어 있다. 이걸 판단에 반영해라.
            - 당사자 자료 / 인터뷰·직접 인용
                본인의 생각·의도·경험에 대한 주장이라면 이쪽이 가장 적합한 근거다.
            - 공식 자료
                날짜·숫자·통계에 대한 주장이라면 이쪽이 가장 적합한 근거다.
            - 언론 보도 / 2차 자료
                위 자료가 없을 때 참고한다.

            **자료끼리 다른 말을 하면 한쪽을 임의로 진실로 정하지 마라.**
            예를 들어 본인 인터뷰와 요약 기사가 다르면,
            "어느 쪽이 맞다" 가 아니라 "자료에 따라 설명이 다릅니다" 라고 적고
            각각 무엇이라 하는지 쓴다. 그게 제작자가 판단할 재료다.
            이 경우 verdict 는 UNVERIFIED_CLAIM 을 쓴다.

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
    private final SourceClassifier sourceClassifier;

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
            List<String> queries = claim.queriesOrFallback();
            if (queries.isEmpty()) {
                continue;
            }
            String query = queries.get(0);

            // 기간 제한 없이 찾는다.
            // 예전에는 searchRecent 를 써서 최근 30일 기사만 뒤졌다.
            // "그 회사 2019년에 설립됐죠" 같은 건 그러면 아예 안 나온다.
            List<Evidence> evidence = gather(newsClient, queries, claim);
            if (evidence.isEmpty()) {
                log.info("[fact-check] '{}' 관련 자료 없음 → 건너뜀", query);
                continue;
            }

            List<NewsSearchClient.NewsItem> news = evidence.stream().map(Evidence::item).toList();
            Verdict verdict = verify(today, claim, evidence);
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
            // relevantContext 에는 기사에서 확인된 내용을 넣는다.
            // 사용자가 링크를 열기 전에 "이 자료에 뭐가 있는지" 를 먼저 볼 수 있다.
            Map<String, ReferenceSourceType> sourceTypes = new LinkedHashMap<>();
            for (Evidence e : evidence) {
                sourceTypes.put(
                        e.item().link() == null ? e.item().title() : e.item().link(),
                        e.sourceType());
            }
            finding.adoptReferences(NewsReferenceSupport.pick(
                    news, verdict.sources(), hasEvidence ? correction : null, sourceTypes));
            findings.add(finding);

            log.info("[fact-check] '{}' → {} (score={}, 참고자료 {}건)",
                    query, category, score, finding.getReferences().size());
        }

        log.info("[fact-check] videoId={} 주장={}개 findings={}",
                context.video().getId(), claims.size(), findings.size());
        return findings;
    }

    /**
     * 검색어들로 자료를 모으고, 주장 성격에 맞는 순서로 정렬한다.
     *
     * 검색어를 여러 개 쓰는 이유는, 본인 발언을 노린 검색어와 일반 검색어가
     * 서로 다른 결과를 주기 때문이다. 첫 번째가 비면 두 번째로 보완한다.
     */
    private List<Evidence> gather(NewsSearchClient client, List<String> queries, Claim claim) {
        Map<String, Evidence> byUrl = new LinkedHashMap<>();

        for (String query : queries) {
            for (NewsSearchClient.NewsItem item : client.searchArchive(query, NEWS_PER_CLAIM)) {
                String key = item.link() == null ? item.title() : item.link();
                if (key == null || byUrl.containsKey(key)) {
                    continue;   // 검색어가 달라도 같은 기사가 겹친다
                }
                byUrl.put(key, new Evidence(item, sourceClassifier.classify(item)));
            }
            if (byUrl.size() >= NEWS_PER_CLAIM) {
                break;   // 충분히 모였으면 추가 검색을 하지 않는다
            }
        }

        ClaimType type = claim.type();
        return byUrl.values().stream()
                // 주장 성격에 맞는 자료를 위로. 본인 생각이면 본인 말, 숫자면 공식 자료.
                .sorted(Comparator.comparingInt(
                        (Evidence e) -> e.sourceType().priorityFor(type)).reversed())
                .limit(TOP_EVIDENCE)
                .toList();
    }

    /** 검색 결과 하나와 그 자료가 원출처에 얼마나 가까운지 */
    record Evidence(NewsSearchClient.NewsItem item, ReferenceSourceType sourceType) {}

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

    private Verdict verify(String today, Claim claim, List<Evidence> evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("오늘 날짜: ").append(today).append("\n\n");
        prompt.append("영상에서 나온 주장: ").append(claim.claim()).append("\n");
        if (claim.subject() != null && !claim.subject().isBlank()) {
            prompt.append("주장의 대상: ").append(claim.subject()).append("\n");
        }
        prompt.append("주장의 성격: ").append(claim.type()).append("\n\n");
        prompt.append("찾은 자료 (적합한 순):\n");

        for (int i = 0; i < evidence.size(); i++) {
            Evidence e = evidence.get(i);
            NewsSearchClient.NewsItem item = e.item();
            prompt.append("[%d] (%s · %s) %s%n    %s%n".formatted(
                    i,
                    e.sourceType().getLabel(),
                    item.pubDate() == null || item.pubDate().isBlank() ? "날짜미상" : item.pubDate(),
                    item.title(),
                    item.description() == null ? "" : item.description()));
        }

        return openAiClient.completeAsJson(VERIFY_PROMPT, prompt.toString(), Verdict.class)
                .orElse(null);
    }

    record ClaimResult(List<Claim> claims) {}

    record Claim(Integer index, String claim, String subject,
                 String claimType, List<String> searchQueries) {

        /** 검색에 쓸 말. 여러 개면 첫 번째가 당사자 자료를 노린 검색어다. */
        List<String> queriesOrFallback() {
            if (searchQueries != null && !searchQueries.isEmpty()) {
                return searchQueries.stream()
                        .filter(q -> q != null && !q.isBlank())
                        .limit(2)
                        .toList();
            }
            return claim == null || claim.isBlank() ? List.of() : List.of(claim);
        }

        ClaimType type() {
            return ClaimType.fromOrDefault(claimType);
        }
    }

    /** sources 는 판단 근거가 된 기사 번호. 참고 자료로 저장한다. */
    record Verdict(String verdict, Double score, String reason,
                   String correction, List<Integer> sources) {}
}
