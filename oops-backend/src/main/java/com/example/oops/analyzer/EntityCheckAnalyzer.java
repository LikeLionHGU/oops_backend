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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 언급되거나 화면에 적힌 이름·날짜·수치가 맞는지 확인한다.
 *
 * 토크나 인터뷰는 즉흥적으로 말하기 때문에 사람 이름, 소속, 연도, 숫자가
 * 자주 어긋난다. 제작자도 편집자도 그 자리에서는 맞다고 믿기 때문에
 * 반복해서 봐도 걸러지지 않는다. 전형적인 검수 사각지대다.
 *
 * **말한 것뿐 아니라 화면에 박은 것도 본다.**
 * 편집자는 지난 영상의 자막 템플릿을 복사해서 쓴다.
 * 그러면서 연도나 숫자만 고치는 걸 잊는다.
 * "2023년 발매" 라고 적힌 자막이 2024년 영상에 그대로 남는 식이다.
 * 말로 한 실수는 다시 들으면 걸리지만, 화면에 박힌 숫자는
 * 만든 사람이 맞다고 믿고 넣은 것이라 몇 번을 봐도 안 걸린다.
 *
 * 도덕 판단이 아니라 단순 정확성 문제라서, 확인만 하면 해결된다.
 *
 * LLM 은 세부 사실을 정확히 외우지 못하고 학습 시점 이후는 아예 모른다.
 * 그래서 검색을 끼워 세 단계로 나눴다.
 *
 *   1. 대본·화면글자에서 확인이 필요한 대목을 뽑는다   (LLM)
 *   2. 그 내용을 뉴스에서 찾아본다                      (검색)
 *   3. 기사와 대조해 어긋나는지 본다                    (LLM)
 *
 * 맞는 것은 보고하지 않는다. 확인이 필요한 것만 올린다.
 *
 * 이 분석기는 **FACT_CHECK 후보를 만들 수 있는 유일한 곳**이다.
 * 사실 확인 카드에는 항상 참고 자료가 붙어야 하고,
 * 자료를 붙이려면 검색을 해야 하는데 검색은 여기서만 한다.
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

    /**
     * 프롬프트에 넣을 화면 글자 줄 수 상한.
     *
     * OCR 은 같은 자막을 프레임마다 다시 읽기 때문에 중복을 걷어내도 양이 많다.
     * 대본까지 함께 넣는데 화면 글자가 수백 줄이면 모델이 앞쪽만 보고 만다.
     */
    private static final int MAX_SCREEN_LINES = 80;

    /** 이보다 짧은 화면 글자는 확인할 사실이 담기지 않는다 */
    private static final int MIN_SCREEN_TEXT_LENGTH = 4;

    private static final String EXTRACT_PROMPT = """
            너는 영상 공개 전에 확인할 지점을 짚어주는 검수 보조자다.
            영상에서 나온 줄들을 받아서, 사실 확인이 필요한 대목만 뽑아낸다.

            각 줄 앞에는 그게 어디서 나왔는지가 붙어 있다.
            - (발언) 출연자가 말한 것. 음성 인식 결과다.
            - (화면) 화면에 글자로 박혀 있는 것. 편집 자막이나 자료 화면이다.

            **(화면) 줄을 특히 눈여겨봐라.**
            편집자는 지난 영상의 자막을 복사해서 쓰다가 숫자만 고치는 걸 잊는다.
            발매 연도, 나이, 순위, 소속, 직함이 옛날 값 그대로 남는 일이 잦다.
            말로 한 실수는 다시 들으면 걸리지만 화면에 박힌 숫자는
            만든 사람이 맞다고 믿고 넣은 것이라 몇 번을 봐도 안 걸린다.
            그래서 (화면) 줄에 연도·숫자·직함·소속이 있으면 적극적으로 뽑아라.

            대화형 영상에서는 즉흥적으로 말하다 보니 이런 것이 자주 어긋난다.
            이건 도덕 판단이 아니라 단순 정확성 문제라서, 확인만 하면 해결된다.

            뽑아야 하는 것:
            - 사람 이름, 직함, 소속 ("OO 대표가", "그 회사 CEO 였던")
            - 회사명, 기관명, 브랜드명
            - 연도, 날짜, 기간 ("2019년에", "3년 전에")
            - 숫자와 수치 ("100만 명이", "두 배로 늘었다")
            - 사건명과 그 사건에 대한 서술
            - 작품명, 프로그램명

            **역사적 사실은 특히 집중해서 봐라.**
            대화 중에 역사 이야기가 나오면 연도와 순서가 자주 어긋난다.
            말하는 사람도 듣는 사람도 대략 맞다고 느껴서 그냥 넘어간다.
            제작자가 스스로 알아채기 가장 어려운 대목이라 여기서 값어치가 나온다.

            - 사건이 일어난 연도·시기      "한국전쟁은 1951년에 시작됐죠"
            - 사건의 이름                  "그때 그 사건이 OO사태였잖아요"
            - 관련 인물과 그 역할          "그 사건은 X가 주도했어요"
            - 관련 기관·단체
            - 사건이 일어난 장소
            - 사건의 전개 순서             "A 가 먼저 있었고 그다음 B 였죠"
            - 확인 가능한 원인·결과 주장
            - 역사적 수치                  "그때 O만 명이 참여했다"

            뽑지 말아야 하는 것:
            - 의견, 감상, 추측 ("제 생각엔", "아마도")
            - 검증할 수 없는 개인 경험
            - 상식 수준의 일반론
            - 진행 멘트, 인사말
            - **지금 있는 자리나 눈앞의 상황에 대한 말**
              "여기 롯데리아 없나?", "이 가게 문 닫았네" 같은 것.
              화자가 그 자리에서 보고 하는 말이라 기사로 확인할 수 없다.
            - **눈앞에 있는 가게의 가격·메뉴·영업 정보**
              가격표, 메뉴판, 간판, 영수증에 적힌 숫자가 여기 해당한다.
              "김치찌개 8000원" 은 **그 가게의 값**이다.
              기사에 나오는 "김치찌개 평균 가격" 과는 다른 이야기라 대조할 수 없다.
              그런데도 검색어가 겹쳐서 "기사와 다르다" 는 카드가 만들어진다.
              화면에 가격이 찍혀 있어도 뽑지 마라.
            - **채널이나 출연자 자신에 대한 정보**
              구독자 수, 조회수, 채널 이력 같은 것.
              기사에 나올 리 없고 나와도 시점이 다르다.
            - 농담이나 과장이 분명한 수치 ("백만 번은 말했다")
            - **글자가 깨져 뜻을 알 수 없는 (화면) 줄**
              화면 글자는 기계가 읽은 것이라 자주 깨진다.
              무슨 말인지 확실하지 않으면 뽑지 마라. 깨진 글자로 검색하면
              엉뚱한 기사가 나오고, 그걸로 "틀렸다" 는 카드가 만들어진다.
            - **채널 로고, 워터마크, 구독 안내 같은 화면 고정 문구**

            반드시 이 JSON 형식으로만 답한다:
            {"claims":[{"index":0,"claim":"확인할 내용을 한 문장으로","subject":"누구·무엇에 대한 이야기인지",
                        "claimType":"DATE","searchQueries":["검색어1","검색어2"]}]}

            index 는 그 내용이 나온 줄 번호다. 대괄호 안의 숫자를 그대로 쓴다.

            claimType 은 다음 중 하나다. **이 값에 따라 어떤 자료를 먼저 볼지가 달라진다.**
            - PERSONAL_STATEMENT : 본인의 생각·의도·경험. "그때 이런 마음이었다고 했다"
            - DATE   : 연도·날짜·기간
            - NUMBER : 수치·통계·금액
            - ENTITY : 인물·회사·기관·작품의 이름이나 관계
            - EVENT  : 사건과 그에 대한 서술. **역사적 사건은 여기다**
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

            **역사적 사건이라면 자료 순서가 다르다.**
              1. 정부·국가기록원·공공기관 자료
              2. 공식 기록기관·박물관·관련 재단
              3. 학술 자료
              4. 신뢰도 높은 언론
              5. 그 밖의 2차 자료

            다만 지금 검색은 뉴스 중심이라 1~3번이 잘 안 나온다.
            뉴스만 보고 "공식 기록과 다르다" 고 단정하지 마라.
            자료에서 확인되는 것만 적고, 부족하면 UNVERIFIED_CLAIM 을 써라.

            **역사 이야기에서 특히 조심할 것.**
            "역사왜곡입니다", "잘못된 역사관입니다" 라고 쓰지 마라. 그건 판정이다.
            무엇이 어떻게 다른지만 적어라.
              좋은 예: "영상에서는 1951년이라고 했는데 자료에는 1950년으로 나옵니다."
              좋은 예: "영상의 설명과 자료에서 사건의 전개 순서가 다르게 나옵니다."
              나쁜 예: "역사적 사실을 왜곡하고 있습니다."

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
            - **특정 가게의 값을 일반 시세 기사와 비교하지 마라.**
              영상에 "김치찌개 8000원" 이 나오고 기사에 "평균 9000원" 이 있어도
              그건 틀린 것이 아니다. 가게마다 값이 다른 게 당연하다.
              개별 업소의 가격·메뉴·영업시간은 항상 OK 다.
            - 기사에 없다고 틀린 것은 아니다.
              뒷받침할 내용이 없으면서 영상에서 단정적으로 말했을 때만
              UNVERIFIED_CLAIM 을 쓴다. 그냥 안 나온다고 쓰지 마라.
            - 기사끼리 엇갈리면 UNVERIFIED_CLAIM 이다.
            - 반올림이나 표현 차이는 넘어간다. 의미가 달라질 때만 잡는다.
            - **화면에 박힌 글자는 기계가 읽은 것이라 오탈자가 섞인다.**
              원문이 깨져 보이면 OK 를 반환해라.
              글자를 잘못 읽은 것을 "사실이 틀렸다" 로 올리면
              제작자는 고칠 것이 없는 카드를 받게 된다.
              화면 글자는 **숫자나 연도가 자료와 분명히 다를 때만** 잡는다.
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
    private final com.example.oops.config.OopsProperties oopsProperties;

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
        //
        // 화면 글자까지 볼 때만 대본 없이도 돌 수 있다.
        // 발언만 보는 기본 설정에서 대본이 없으면 확인할 것이 없다.
        boolean hasInput = context.hasTranscript()
                || (screenTextEnabled() && context.hasScreenText());
        return hasInput && openAiClient.isEnabled() && newsClient() != null;
    }

    /** 사실 확인이 화면 글자까지 볼지. 기본은 발언만. */
    private boolean screenTextEnabled() {
        return oopsProperties.analysis() != null
                && oopsProperties.analysis().factCheckScreenTextOrDefault();
    }

    private NewsSearchClient newsClient() {
        return newsSearchClients.stream()
                .filter(NewsSearchClient::isEnabled)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        NewsSearchClient newsClient = newsClient();
        if (newsClient == null) {
            return List.of();
        }

        // 발언과 화면 글자를 한 목록으로 합친다.
        // 어디서 나왔는지는 FactLine.type 이 들고 있어서, 카드를 만들 때
        // 발언이면 text 로, 화면 글자면 captionText + 화면 캡처로 저장한다.
        List<FactLine> lines = collectLines(context);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<Claim> claims = extractClaims(lines);
        if (claims.isEmpty()) {
            log.info("[fact-check] videoId={} 검증할 주장 없음", context.video().getId());
            return List.of();
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        List<RiskFinding> findings = new ArrayList<>();

        for (Claim claim : claims.stream().limit(MAX_CLAIMS).toList()) {
            if (claim.index() == null || claim.index() < 0 || claim.index() >= lines.size()) {
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
            Verdict verdict = verify(today, claim, lines.get(claim.index()), evidence);
            if (verdict == null || verdict.verdict() == null || "OK".equalsIgnoreCase(verdict.verdict())) {
                continue;
            }

            RiskCategory category = RiskCategory.fromOrDefault(
                    verdict.verdict(), RiskCategory.UNVERIFIED_CLAIM);
            double score = verdict.score() == null
                    ? 0.6 : Math.max(0.0, Math.min(1.0, verdict.score()));

            FactLine line = lines.get(claim.index());
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

            // 발언이면 text, 화면 글자면 captionText 에 넣는다.
            // 프론트는 type 으로 분기하므로 여기서 제대로 갈라놓지 않으면
            // 화면에 박힌 자막이 "출연자가 이렇게 말했습니다" 로 보인다.
            boolean caption = line.type() == TimelineEventType.CAPTION;

            RiskFinding.RiskFindingBuilder builder = RiskFinding.builder()
                    .video(context.video())
                    .eventType(line.type())
                    .category(category)
                    .source(caption ? EvidenceSource.VISION : EvidenceSource.SUBTITLE)
                    .score(score)
                    .startMs(line.startMs())
                    .endMs(line.endMs())
                    .reason(reason)
                    .target(query);

            if (caption) {
                builder.captionText(line.text()).frame(line.frame());
            } else {
                builder.text(line.text());
            }
            RiskFinding finding = builder.build();

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

            log.info("[fact-check] {} '{}' → {} (score={}, 참고자료 {}건)",
                    caption ? "화면" : "발언", query, category, score,
                    finding.getReferences().size());
        }

        long captionFindings = findings.stream()
                .filter(f -> f.getEventType() == TimelineEventType.CAPTION).count();
        log.info("[fact-check] videoId={} 검토줄={}개 주장={}개 findings={} (발언 {} / 화면 {})",
                context.video().getId(), lines.size(), claims.size(), findings.size(),
                findings.size() - captionFindings, captionFindings);
        return findings;
    }

    /**
     * 발언과 화면 글자를 한 목록으로 합친다.
     *
     * 두 번 나눠 부르지 않고 한 번에 넣는 이유는,
     * "화면에는 2023년이라고 적혀 있는데 말로는 2024년이라 했다" 같은
     * 어긋남을 모델이 같은 화면에서 볼 수 있어야 하기 때문이다.
     *
     * 화면 글자는 손질이 필요하다. OCR 은 같은 자막을 프레임마다 다시 읽어서
     * 똑같은 줄이 수십 개씩 들어온다. 그대로 넣으면 대본이 밀려난다.
     */
    private List<FactLine> collectLines(AnalysisContext context) {
        List<FactLine> lines = new ArrayList<>();

        if (context.hasTranscript()) {
            for (TranscriptSegment s : context.transcript()) {
                if (s.getText() != null && !s.getText().isBlank()) {
                    lines.add(new FactLine(TimelineEventType.SPEECH,
                            s.getStartMs(), s.getEndMs(), s.getText().trim(), null));
                }
            }
        }

        // 화면 글자는 기본으로 안 본다. OopsProperties.Analysis 주석 참고.
        if (screenTextEnabled() && context.hasScreenText()) {
            Set<String> seen = new HashSet<>();
            int added = 0;
            int menuSkipped = 0;
            for (ScreenText s : context.screenTexts()) {
                if (added >= MAX_SCREEN_LINES) break;

                String text = s.getText() == null ? "" : s.getText().trim();
                if (text.length() < MIN_SCREEN_TEXT_LENGTH) {
                    continue;   // "ㅋㅋ", "1" 같은 건 확인할 사실이 없다
                }

                // 메뉴판·가격표는 확인할 '사실' 이 아니라 그 가게의 값이다.
                // 이걸 안 걸러서 "평균 가격" 기사와 대조하는 오탐이 반복됐다.
                // 프롬프트로도 막아뒀지만 여기가 문이다.
                if (ScreenTextShape.looksLikePriceList(text)) {
                    menuSkipped++;
                    continue;
                }
                // 공백과 기호를 뺀 형태로 중복을 본다.
                // OCR 이 같은 자막을 읽을 때마다 띄어쓰기가 조금씩 달라진다.
                if (!seen.add(text.replaceAll("\\s+", ""))) {
                    continue;
                }
                lines.add(new FactLine(TimelineEventType.CAPTION,
                        s.getStartMs(), s.getEndMs(), text, s.getFrame()));
                added++;
            }
            if (menuSkipped > 0) {
                log.info("[fact-check] 가격표로 보이는 화면 글자 {}건은 확인 대상에서 뺌", menuSkipped);
            }
        }

        lines.sort(Comparator.comparingLong(FactLine::startMs));
        return lines;
    }

    /**
     * 사실 확인 대상 한 줄. 발언이든 화면 글자든 여기로 모인다.
     *
     * @param frame 화면 글자일 때 그 장면 캡처. 발언이면 null
     */
    record FactLine(TimelineEventType type, long startMs, long endMs,
                    String text, VideoFrame frame) {

        /** 프롬프트에 붙일 출처 표시 */
        String sourceLabel() {
            return type == TimelineEventType.CAPTION ? "화면" : "발언";
        }
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

    private List<Claim> extractClaims(List<FactLine> lines) {
        StringBuilder prompt = new StringBuilder(
                "영상에서 나온 줄들이다. 검증 가능한 사실 주장을 뽑아라.\n\n");
        for (int i = 0; i < lines.size(); i++) {
            FactLine line = lines.get(i);
            prompt.append("[%d] (%s) %s%n".formatted(i, line.sourceLabel(), line.text()));
        }

        ClaimResult result = openAiClient
                .completeAsJson(EXTRACT_PROMPT, prompt.toString(), ClaimResult.class)
                .orElse(null);
        return result == null || result.claims() == null ? List.of() : result.claims();
    }

    private Verdict verify(String today, Claim claim, FactLine line, List<Evidence> evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("오늘 날짜: ").append(today).append("\n\n");
        prompt.append("영상에서 나온 주장: ").append(claim.claim()).append("\n");
        prompt.append("나온 곳: ").append(line.type() == TimelineEventType.CAPTION
                ? "화면에 박힌 글자 (편집 자막). 원문: \"" + line.text() + "\""
                : "출연자 발언. 원문: \"" + line.text() + "\"").append("\n");
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
