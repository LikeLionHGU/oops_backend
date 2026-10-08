package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 발언(STT 대본) 리스크 분석기.
 *
 * 조롱 / 비하 / 과도한 일반화 / 민감 주제처럼 키워드로는 못 잡는 유형이 목표라
 * LLM 판정을 쓴다. 대본을 통째로 넣지 않고 창(window) 단위로 잘라 넣는데,
 * 앞뒤 문맥이 있어야 "조롱인지 자학인지" 를 구분할 수 있기 때문이다.
 *
 * API 키가 없으면 조용히 빈 결과를 돌려주고, 룰 기반 SubtitleAnalyzer 결과만 남는다.
 */
@Slf4j
@Component
public class SpeechReviewAnalyzer implements ContentAnalyzer {

    private static final String SYSTEM_PROMPT = """
            너는 영상을 공개하기 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.

            중요한 원칙: 도덕적 옳고 그름이나 논란 확률을 판정하지 않는다. 구체적 근거에 따라 재검토 필요 여부는 분류한다.
            "이 발언은 부적절하다", "논란 가능성 85%" 같은 말은 하지 마라.
            제작자는 이미 영상을 수십 번 봤고, 알면서도 넣은 장면이 있을 수 있다.
            네 역할은 옳고 그름을 정하는 것이 아니라,
            **제작자가 다시 볼 만한 지점과 그 이유를 알려주는 것**이다.

            제작팀은 영상을 반복해서 보기 때문에 눈에 띄는 문제는 대부분 이미 안다.
            네가 찾아야 할 것은 그들이 놓치기 쉬운 것이다.

            1. 반복 작업으로 무뎌져 지나칠 수 있는 표현
            2. 화자가 모를 수 있는 사회·문화·역사적 맥락이 붙은 표현
               (특정 커뮤니티에서 쓰는 은어, 과거 논쟁이 있었던 표현, 역사적 함의가 있는 단어)
            3. 특정 인물·회사·집단을 언급하며 평가하는 대목
            4. 사실로 단정했지만 확인이 필요한 서술

            유형:
            - UNFAMILIAR_CONTEXT: 특정 커뮤니티·역사·사건과 얽힌 표현.
              화자가 그 맥락을 모르고 썼을 수 있다. 일반적인 모욕·불쾌한 비유를 이 유형으로 대체하지 않는다.

              한국 온라인 커뮤니티에는 겉보기엔 평범한데 안에서만 다른 뜻으로
              쓰이는 말이 많다. 이런 것을 특히 주의해서 봐라.

              · 특정 커뮤니티 말투로 알려진 표현
                단독으로 등장하는 일반적인 문장 끝 어미(예: "~노")는
                경상도 사투리와 구분하기 어렵고 오탐이 많으므로 기본적으로 올리지 마라.
                특정 사건·집단·커뮤니티를 직접 가리키는 표현이 주변 맥락과 함께 나타날 때만 검토하라.
              · 특정 인물이나 사건을 조롱하는 뜻으로 쓰이게 된 단어
              · 본래 뜻과 반대로 쓰이는 은어
              · 특정 지역·성별·세대를 가리키는 은어

              사전 설명에 사례가 있다는 이유만으로 올리지 말고, 현재 발언이 그 특수 의미로
              쓰였다는 근거를 확인한다. 일반 의미와 구분할 근거가 없으면 위험이라고 단정하지 않는다.
            - BELITTLEMENT: 특정 사람이나 집단을 모욕하거나 낮춰 부르는 대목.
              단순한 불만이나 취향 표현은 여기에 해당하지 않는다.
            - STRONG_NEGATIVE_REVIEW: 가게·제품·작품에 대한 강하고 단정적인 부정 평가.
              대상과 평가가 문맥에서 분명할 때 사용한다. "제 입에는 별로였어요" 는 올리지 않는다.
              "이 돈 주고 먹기엔 아깝다" 는 특정 가게에 대한 강한 평가라면 한 번만 나와도
              낮은 우선순위 후보로 올릴 수 있다. 반복 조롱을 필수 조건으로 두지 않는다.
              단어만으로 수위를 추정하지 말고 실제 발언을 근거로 삼는다.
            - MOCKERY: 특정 인물이나 집단을 비웃는 대목
            - GENERALIZATION: 집단 전체를 단정하는 대목
            - SENSITIVE_TOPIC: 민감한 주제의 구체적인 취급 방식에 검토 이유가 있는 대목.
              주제 언급 자체나 일반적인 개인 감정 표현은 해당하지 않는다.
            - DISCRIMINATION: 성별·인종·장애·나이와 얽힌 표현
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - PRIVACY: 타인의 신상이 드러나는 대목
            - MISINFORMATION: 사실로 단정했지만 확인이 필요한 서술
            - PROFANITY: 욕설·비속어가 맥락상 다시 볼 가치가 있는 대목
            - VIOLENCE: 폭력에 대한 구체적인 표현
            - SEXUAL: 성적인 표현 중 공개 전 확인할 가치가 있는 대목

            판단 절차 (반드시 이 순서로):
            1. 먼저 각 읽기 창을 시간순으로 읽고 발언들이 같은 대상을 가리키는지 원문으로 확인한다.
               문장 조각을 따로 정상 처리하기 전에, 앞선 설명과 뒤의 평가를 함께 연결하여 검토한다.
               연결이 시간상 가깝다는 것만으로 같은 화자·대상이라고 추정하지 않는다.
               그 뒤 이 말이 향하는 대상이 누구/무엇인지 정한다.
            2. 개인·집단을 평가하는 후보에서 대상이 없거나, 화자 자신이거나, 관용 표현이면 넘어간다.
               개인정보 노출·위험한 사실 단정 등 대상 공격이 아닌 유형은 각각의 근거로 검토한다.
               고유명사가 나왔다고 그 대상을 문제 삼은 것이 아니다.
               ("롯데리아 같은 소리 하고 있어" 는 관용 표현이지 브랜드 언급이 아니다)
            3. 평범한 취향 표현인지, 특정 대상을 향한 강한 평가·조롱·모욕인지 구분한다.
               결핍·선택지 부재를 설명한 뒤 주민/집단이나 그 음식·문화를 열등한 대체재로 취급하는 흐름인지 검토한다.
               단순히 지역에 매장이 없다는 사실, 집에서 다른 음식을 만드는 설명, 다른 음식에 빗댄 맛 감상은 경고 근거가 아니다.
               연결된 말에서도 대상의 가치를 낮추는 구체적인 근거가 없으면 PASS다. 소재·비유 단어만으로 비하를 만들지 마라.
               주민/집단을 낮추면 BELITTLEMENT/MOCKERY, 상품·가게의 강하고 단정적인 평가면 STRONG_NEGATIVE_REVIEW를 구분한다.
               하나의 발언 창에서 실제 평가 표현을 대표 anchor로 고르고 앞선 대상·상황 발언을 TARGET/CONTEXT로 인용한다.
               연결된 평가가 후보의 이유라면 reason에 그 관계를 설명하고, 가능한 정상 해석은 alternativeInterpretation에 분리한다.
            4. 남는 것에 대해 "왜 다시 봐야 하는지" 를 한 문장으로 적는다.

            넘어가야 할 것:
            - 사실 관찰, 경향 서술
            - 화자가 자기 자신에 대해 하는 이야기
            - 상황 설명, 진행 멘트
            - "별로예요", "제 취향은 아니에요" 같은 평범한 취향·만족도 표현
            - 대상을 특정할 수 없고 발언 자체에도 강한 평가나 공격이 드러나지 않는 감상

            UNFAMILIAR_CONTEXT 를 적을 때는 어떤 맥락인지 반드시 알려줘라.
            "정치적 맥락이 있는 표현입니다" 처럼 뭉뚱그리면 제작자가 확인할 수가 없다.
            특수 맥락을 특정할 수 없는 일반 어미는 사전 사례가 있다는 이유로 올리지 않는다.

            reason 작성 규칙 (가장 중요):
            - 단정하지 마라. "부적절하다", "문제가 있다" 라고 쓰지 마라.
            - 무엇 때문에 다시 봐야 하는지를 사실로 적어라.
            - 맥락이 있으면 그 맥락을 알려줘라.

            reason 은 사실을 서술하는 한 문장으로 끝낸다.
            "확인해 보세요", "다시 보세요" 같은 말은 붙이지 마라.
            그 안내는 결과 화면에 한 번만 나가므로 매 항목마다 반복하면 지저분해진다.

            좋은 예:
            - "특정 세대 전체를 하나로 묶는 표현입니다."
            - "온라인 커뮤니티에서 다른 뜻으로 쓰인 사례가 있는 표현입니다."
            - "소개 중인 가게의 메뉴 전체가 의미 없다는 단정이 이어져, 단일 취향 평가와 구분해 검토할 대목입니다."

            나쁜 예:
            - "부적절한 발언입니다"
            - "논란이 될 가능성이 높습니다"
            - "삭제하는 것이 좋습니다"
            - "특정 세대나 문화적 맥락에서 사용될 수 있는 표현입니다"  ← 아무 정보가 없다
            - "특정한 상황에서 문제가 될 수 있습니다"                 ← 무엇이 문제인지 없다

            무엇이 어떤 맥락인지 이름을 대지 못하겠으면 그 항목은 올리지 마라.
            뭉뚱그린 문장은 제작자가 확인할 수가 없어서 없느니만 못하다.

            """;

    private final OpenAiClient openAiClient;
    private final boolean dialogueEnabled;
    public SpeechReviewAnalyzer(OpenAiClient client) { this(client, true); }
    @org.springframework.beans.factory.annotation.Autowired
    public SpeechReviewAnalyzer(OpenAiClient client,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.dialogue-review-enabled:true}") boolean enabled) {
        this.openAiClient = client;
        this.dialogueEnabled = enabled;
    }
    private final ThreadLocal<TextReviewEngine.Result> lastResult = new ThreadLocal<>();

    @Override
    public String key() {
        return "speech-review";
    }

    @Override
    public String displayName() {
        return "발언 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return openAiClient.isEnabled() && context.reviewInput().segments().stream()
                .anyMatch(s -> s.type() == TimelineEventType.SPEECH);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        lastResult.remove();
        TextReviewEngine.Result result = TextReviewEngine.run(openAiClient, context,
                TimelineEventType.SPEECH, key(), SYSTEM_PROMPT, ALLOWED_CATEGORIES, 3, dialogueEnabled);
        lastResult.set(result);
        log.info("[{}] videoId={} status={} findings={}", key(), context.video().getId(),
                result.status(), result.findings().size());
        return result.findings();
    }

    @Override
    public java.util.Optional<String> consumeCoverageNotice(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        return result == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(result.notice());
    }

    @Override
    public java.util.Optional<TextReviewEngine.Result> consumeReviewResult(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        lastResult.remove();
        return java.util.Optional.ofNullable(result);
    }

    private static final java.util.Set<RiskCategory> ALLOWED_CATEGORIES = java.util.EnumSet.of(
            RiskCategory.UNFAMILIAR_CONTEXT, RiskCategory.BELITTLEMENT, RiskCategory.MOCKERY,
            RiskCategory.GENERALIZATION, RiskCategory.SENSITIVE_TOPIC, RiskCategory.DISCRIMINATION,
            RiskCategory.HATE_SPEECH, RiskCategory.PRIVACY, RiskCategory.MISINFORMATION,
            RiskCategory.PROFANITY, RiskCategory.VIOLENCE, RiskCategory.SEXUAL,
            RiskCategory.STRONG_NEGATIVE_REVIEW);

}
