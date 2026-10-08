package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 화면에 박힌 자막(OCR)을 LLM 으로 판정한다.
 *
 * ScreenTextAnalyzer(룰)와 역할이 다르다.
 * 룰은 욕설·개인정보처럼 사전에 열거할 수 있는 것만 잡는다.
 * 하지만 "특정 시기에 민감한 정치 이슈" 같은 건 키워드로 나열할 수 없다.
 * 편집 자막에만 등장하고 발언에는 없는 민감 내용은 여기서만 잡힌다.
 *
 * OCR 추정 복원은 원문과 분리하며, 실제 원문에서 검증한 인용만 후보의 근거로 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenTextReviewAnalyzer implements ContentAnalyzer {


    private static final String SYSTEM_PROMPT = ReviewJudgmentPolicy.PROMPT + """
            # 화면 글자 입력 한계와 유형 정의
            너는 영상 공개 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.
            서버가 편집 문구로 선별한 OCR을 받는다. 선별 결과는 추정이며 실제 자막 출처를 확정한 것이 아니다.

            원칙: 도덕적 판정이나 논란 확률을 제시하지 않는다. 근거에 따라 재검토 필요 여부는 분류한다.

            입력의 CAPTION은 화면 글자, SPEECH는 음성 전사다. 화면 문구를 화자의 직접 발언으로 바꾸지 마라.
            실제 화면·배치·표정·억양은 제공되지 않았다. editorial 역할도 작성자의 악의·의도를 증명하지 않는다.
            간판·메뉴판·로고가 문맥에 있더라도 자막 표현 평가나 가게 신원의 확정 근거로 삼지 마라.
            제작자가 이미 봤을 것이라는 이유로 분명한 문제 표현을 제외하지 않는다.

            중요: 이 텍스트는 OCR 결과라 글자가 자주 깨져 있다.
            "재선거" 가 "재선커", "재신거" 처럼 나올 수 있다.
            복원 가능성이 있으면 추정 문구를 reading에만 기록한다. 원문 근거를 대체하지 마라.
            깨진 글자 때문에 판단에 필요한 정보가 부족하면 UNCERTAIN으로 기록한다.

            유형:
            - UNFAMILIAR_CONTEXT: 특정 커뮤니티·역사·사건과 얽힌 표현
            - BELITTLEMENT: 특정 사람이나 집단을 모욕하거나 낮춰 부르는 표현
            - STRONG_NEGATIVE_REVIEW: 가게·제품·작품 평가가 문맥상 조롱·모욕적인 가치 절하로 이어지는 경우.
              강한 비판·단정적 표현만으로 경고하지 않는다. 정상 리뷰와 구별되는 추가 맥락을 설명한다.
            - MOCKERY: 특정 인물이나 집단을 비웃는 표현
            - GENERALIZATION: 집단 전체를 단정하는 표현
            - SENSITIVE_TOPIC: 민감한 주제의 취급 방식에 구체적인 검토 이유가 있는 경우.
              단순 주제 언급이나 개인 감정 표현은 제외한다.
            - DISCRIMINATION: 성별·인종·장애·나이와 얽힌 표현
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - PRIVACY: 타인의 비공개 신상·연락처 등 노출. 공개 이름·소속의 단순 언급만으로 단정하지 않는다.
            - PROFANITY: 욕설, 비속어
            - VIOLENCE: 폭력에 대한 구체적인 표현
              실제 폭력/위협과 음식·감각의 비유를 구별한다.
            - GRAPHIC_METAPHOR: 음식·감각 등을 사람의 신체 훼손·섭취에 구체적으로 빗댄 편집 문구.
              특정 피해자나 공격 대상이 없어도 표현 자체의 원문 근거와 대조 해석으로 검토할 수 있다.
              alternativeInterpretation에 음식/감각 비유와 실제 행위 서술을 구별한 이유를 적는다.
              대상이 없으면 target과 대상 관련 필드는 null이다. 신체 단어·가족 호칭만으로 경고하지 않는다.
              기억/정성의 맛 비유, 관용 표현, 식재료 설명, 의료/교육/비판적 인용은 맥락만으로 경고하지 않는다.
              OCR 추정 reading만으로 신체 훼손 표현을 만들어내지 않는다. 원문으로 구별 불가능하면 UNCERTAIN이다.
            - SEXUAL: 성적인 표현 중 공개 전 확인할 가치가 있는 대목

            # 화면 글자 판단 보조
            1. 이 자막이 향하는 대상을 먼저 정한다.
            2. 개인·집단 평가에 대상 연결이 필수인데 확인할 수 없으면 UNCERTAIN이다.
               관용 표현 자체만으로 경고하지 않으며 문맥상 독립적인 모욕·조롱 근거가 있는지 확인한다.
               개인정보 노출·GRAPHIC_METAPHOR 등 대상 공격이 아닌 유형은 각각의 근거로 검토한다.
            3. 취향·강한 비판 자체인지, 문맥상 조롱·모욕으로 이어지는지 구분한다.
            4. 남는 것에 대해 왜 다시 봐야 하는지 적는다.

            넘어가야 할 것:
            - 채널명, 구독, 좋아요, 알림설정 같은 UI 텍스트
            - 사실 전달, 상황 설명, 진행 안내 자막
            단, UI/인용/진행 문구라는 형식만으로 독립적인 욕설·개인정보·공격 표현까지 통과시키지 않는다.

            # 설명 작성
            구체적인 검토 이유가 없으면 후보를 만들지 말고 PASS를 반환한다. 필수 정보 부족은 UNCERTAIN이다.
            출력 계약에서 요구한 평가 항목 자체를 생략하지 마라.
            아래 같은 말은 아무 정보가 없어서 제작자가 확인할 수가 없다. 쓰지 마라.
            - "특정한 상황이나 맥락에서 사용될 수 있는 표현입니다"
            - "문화적 맥락과 관련이 있을 수 있습니다"
            - "민감한 주제를 다루고 있습니다"
            무엇이 어떤 맥락인지 이름을 대지 못하겠으면 올리지 마라.

            reason 은 단정하지 말고, 무엇 때문에 다시 봐야 하는지를 사실로 적어라.
            "부적절합니다" 가 아니라 "이 표현은 ~한 맥락이 있습니다" 형태로 끝낸다.
            "확인해 보세요" 같은 안내는 붙이지 마라. 화면에 한 번만 나간다.

            """;

    private final OpenAiClient openAiClient;
    private final ThreadLocal<TextReviewEngine.Result> lastResult = new ThreadLocal<>();

    @Override
    public String key() {
        return "screen-text-review";
    }

    @Override
    public String displayName() {
        return "화면 자막 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return openAiClient.isEnabled() && context.reviewInput().segments().stream()
                .anyMatch(s -> s.type() == TimelineEventType.CAPTION);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        lastResult.remove();
        TextReviewEngine.Result result = TextReviewEngine.run(openAiClient, context,
                TimelineEventType.CAPTION, key(), SYSTEM_PROMPT, ALLOWED_CATEGORIES, 2);
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
            RiskCategory.HATE_SPEECH, RiskCategory.PRIVACY, RiskCategory.PROFANITY,
            RiskCategory.VIOLENCE, RiskCategory.SEXUAL, RiskCategory.STRONG_NEGATIVE_REVIEW,
            RiskCategory.GRAPHIC_METAPHOR);

}
