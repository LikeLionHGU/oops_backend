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


    private static final String SYSTEM_PROMPT = """
            너는 영상 공개 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.
            화면에 박혀 있던 편집 자막을 OCR 로 읽은 결과를 받는다.

            원칙: 도덕적 판정이나 논란 확률을 제시하지 않는다. 근거에 따라 재검토 필요 여부는 분류한다.

            편집 자막은 편집자가 넣은 것이라 화자의 의도와 다를 수 있다.
            그래서 화자 본인이 최종본을 볼 때 놓치기 쉽다. 여기가 사각지대다.

            중요: 이 텍스트는 OCR 결과라 글자가 자주 깨져 있다.
            "재선거" 가 "재선커", "재신거" 처럼 나올 수 있다.
            복원 가능성이 있으면 추정 문구를 reading에만 기록한다. 원문 근거를 대체하지 마라.
            깨진 글자 때문에 판단에 필요한 정보가 부족하면 UNCERTAIN으로 기록한다.

            유형:
            - UNFAMILIAR_CONTEXT: 특정 커뮤니티·역사·사건과 얽힌 표현
            - BELITTLEMENT: 특정 사람이나 집단을 모욕하거나 낮춰 부르는 표현
            - STRONG_NEGATIVE_REVIEW: 특정 가게·제품·작품에 대한 강하고 단정적인 부정 평가
              단순 취향 표현("제 입에는 별로예요")은 제외한다.
              특정 가게를 향한 "이 돈 주고 먹기엔 아깝다" 같은 강한 평가는
              한 번만 나와도 낮은 우선순위 후보가 될 수 있다.
            - MOCKERY: 특정 인물이나 집단을 비웃는 표현
            - GENERALIZATION: 집단 전체를 단정하는 표현
            - SENSITIVE_TOPIC: 민감한 주제의 취급 방식에 구체적인 검토 이유가 있는 경우.
              단순 주제 언급이나 개인 감정 표현은 제외한다.
            - DISCRIMINATION: 성별·인종·장애·나이와 얽힌 표현
            - HATE_SPEECH: 특정 집단을 향한 혐오 표현
            - PRIVACY: 타인의 이름, 연락처, 소속이 드러남
            - PROFANITY: 욕설, 비속어
            - VIOLENCE: 폭력에 대한 구체적인 표현
            - SEXUAL: 성적인 표현 중 공개 전 확인할 가치가 있는 대목

            판정 절차:
            1. 이 자막이 향하는 대상을 먼저 정한다.
            2. 개인·집단 평가 후보에서 대상이 없거나 관용 표현이면 넘어간다.
               개인정보 노출 등 대상 공격이 아닌 유형은 각각의 근거로 검토한다.
            3. 평범한 취향 표현인지, 강한 평가·조롱·모욕인지 구분한다.
            4. 남는 것에 대해 왜 다시 봐야 하는지 적는다.

            넘어가야 할 것:
            - 채널명, 구독, 좋아요, 알림설정 같은 UI 텍스트
            - 사실 전달, 상황 설명, 진행 안내 자막

            reason 에 구체적인 내용이 없으면 그 항목은 아예 빼라.
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
            RiskCategory.VIOLENCE, RiskCategory.SEXUAL, RiskCategory.STRONG_NEGATIVE_REVIEW);

}
