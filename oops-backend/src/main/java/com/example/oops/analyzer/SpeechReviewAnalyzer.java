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

    private static final String SYSTEM_PROMPT = ReviewJudgmentPolicy.PROMPT + """
            # 발언 입력 한계와 유형 정의
            입력과 판단 한계:
            - 제공된 텍스트와 시간만 읽는다. 실제 영상·억양·표정·화자 신원은 제공되지 않았다.
            - STT의 방언·미완성 표현을 표준어/주민/사건 이름으로 추측 교정하지 마라.
              핵심 의미를 읽는 데 필요한 원문이 불명확하면 UNCERTAIN과 부족 정보를 기록한다.
            - 대상명은 원문 또는 명확한 지시어 연결로 확인한다. 상품 평가를 주민 전체 공격으로 확대하지 마라.
            - 외부 뉴스 검색이나 사전 조회를 이 요청에서 했다고 가정하지 마라.
              특수 의미의 기원·사건 연관성을 확인하지 못하면 구체적인 사실처럼 설명하지 않는다.
            - '~노' 같은 일반 어미·방언만으로 특정 커뮤니티 사용이나 공격 의도를 판정하지 마라.

            검토 유형:
            - BELITTLEMENT: 사람·집단의 능력/존재/가치를 낮추는 표현. 상품 불만과 구별한다.
            - MOCKERY: 대상의 처지·선택·속성을 비웃는 표현. 단순 비교나 친근한 농담으로 자동 확정하지 않는다.
            - STRONG_NEGATIVE_REVIEW: 상품·서비스·가게·작품의 평가가 문맥상 조롱·모욕적인 가치 절하로 이어지는 경우.
              강한 비판·단정적 무가치 평가만으로 이 유형을 적용하지 마라. 정상 리뷰와 구별되는 추가 맥락을 설명한다.
              정당한 리뷰라는 대조 해석보다 검토가 필요한 구체적인 원문 관계가 있어야 한다.
            - GENERALIZATION: 일부 사례를 집단 전체의 속성으로 단정하는 표현.
            - DISCRIMINATION: 성별·인종·장애·나이 등 속성을 근거로 차별/열등함을 표현하는 대목.
            - HATE_SPEECH: 집단을 향한 혐오·배제·비인간화 표현.
            - UNFAMILIAR_CONTEXT: 현재 사용 맥락이 구체적인 커뮤니티·역사·사건의 특수 의미를 뒷받침하는 표현.
              모르는 단어, 일반 모욕, 신체 비유의 대체 유형이 아니다. 단순 가능성만 나열하지 않는다.
            - SENSITIVE_TOPIC: 민감한 주제의 언급 자체가 아니라 취급 방식에 구체적인 검토 근거가 있는 대목.
            - PRIVACY: 타인의 비공개 신상·연락처 등 노출. 이름이나 공개된 소속 단순 언급만으로 단정하지 않는다.
            - MISINFORMATION: 사실처럼 제시된 내용에 구체적인 검토 근거가 있는 대목.
              취향·농담을 사실 주장으로 바꾸지 않는다. 외부 대조 없이 거짓이라고 확정하지 않는다.
            - PROFANITY: 원문 욕설·비속어가 맥락상 다시 볼 이유가 있는 대목.
            - VIOLENCE: 실제 폭력 행위·위협·권유를 다루는 표현. 식감 비유를 실제 폭력 의도로 바꾸지 않는다.
            - GRAPHIC_METAPHOR: 감각·상황을 사람의 신체 훼손·섭취 이미지에 구체적으로 빗대는 표현.
              공격 대상 없이도 표현 자체를 검토할 수 있지만 실제 피해자·행위를 지어내지 않는다.
              alternativeInterpretation에 과장된 비유/식재료 설명/비판적 인용/실제 행위와 구별한 이유를 적는다.
              가족 호칭·신체 단어, 기억/정성의 맛 비유, 관용 표현, 식재료·의료·교육 설명만으로 경고하지 않는다.
            - SEXUAL: 성적 표현의 구체적인 내용·사용 맥락에 검토 이유가 있는 대목.

            # 설명 작성
            reason은 실제 인용의 대상·표현·연결 때문에 왜 다시 볼지 구체적으로 설명한다.
            "부적절하다", "논란 가능성이 높다", "삭제하라", "문화적 맥락일 수 있다" 같은 막연한 판정을 쓰지 마라.
            제작자가 아는지 여부·악의·억양을 추측하지 않는다. 점수는 검토 우선순위이며 논란 확률이 아니다.
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
                TimelineEventType.SPEECH, key(), SYSTEM_PROMPT + "\n" + ContextualComparisonPolicy.PROMPT,
                ALLOWED_CATEGORIES, 3, dialogueEnabled);
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
            RiskCategory.STRONG_NEGATIVE_REVIEW, RiskCategory.GRAPHIC_METAPHOR);

}
