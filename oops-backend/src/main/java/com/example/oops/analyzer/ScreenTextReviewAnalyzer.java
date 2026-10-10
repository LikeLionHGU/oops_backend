package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 선별된 화면 텍스트(OCR)를 STT와 독립적으로 LLM으로 판정한다.
 *
 * ScreenTextAnalyzer(룰)와 역할이 다르다.
 * 룰은 욕설·개인정보처럼 사전에 열거할 수 있는 것만 잡는다.
 * 하지만 "특정 시기에 민감한 정치 이슈" 같은 건 키워드로 나열할 수 없다.
 * 화면 문구를 화자의 직접 발언이나 제작자의 동조로 자동 귀속하지 않는다.
 *
 * OCR 추정 복원은 원문과 분리하며, 실제 원문에서 검증한 인용만 후보의 근거로 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenTextReviewAnalyzer implements ContentAnalyzer {


    private static final String SYSTEM_PROMPT = ReviewJudgmentPolicy.PROMPT + """
            # 화면 텍스트 검수
            공통 판단 기준에 따라 OCR로 추출한 화면 문구에서
            게시 전에 사람이 다시 확인할 구체적인 표현이나 노출 내용을 찾는다.
            화면 글자는 자막뿐 아니라 편집 문구·인용·간판·메뉴판·UI일 수 있다.

            ## 1. 입력의 범위와 한계
            - CAPTION은 OCR로 인식된 화면 글자다.
              이름이 CAPTION이라는 이유로 실제 자막이나 제작자의 편집 문구로 확정하지 않는다.
            - 서버의 문구 선별과 역할 분류는 추정이다.
              선별됐다는 사실이 위험성이나 작성자의 의도를 증명하지 않는다.
            - 실제 화면·글자 배치·표정·장면은 제공되지 않았다.
              화면에 보이지 않는 대상이나 상황을 지어내지 않는다.
            - 화면 문구를 화자의 실제 발언이나 제작자의 동조로 바꾸지 않는다.
              작성자·인용 주체를 확인할 수 없으면 귀속하지 않은 채 문구 자체를 판단한다.

            ## 2. STT와 독립적으로 판단한다
            - 판단과 인용 근거는 현재 요청에 제공된 OCR 원문에서 찾는다.
            - SPEECH가 함께 제공되더라도 발언의 위험성은 이 요청에서 평가하지 않는다.
              STT로 OCR 글자를 복원하거나, 누락된 공격 대상·상황·의도를 채우지 않는다.
            - 같은 시간에 등장하거나 비슷한 단어가 있다는 이유로
              화면 문구와 발언이 같은 주장이라고 판단하지 않는다.
            - STT에 문제가 없다는 이유로 화면 문구의 문제를 제외하지 않는다.
              STT에도 같은 표현이 있다는 이유로 OCR의 정확성이나 위험성을 높이지 않는다.
            - 화면 문구가 없거나 읽을 수 없다는 사실은 영상 전체의 안전을 의미하지 않는다.
              이 요청의 판단 범위는 제공된 화면 텍스트에 한정한다.

            ## 3. 화면 문구의 역할을 구별한다
            - 상품명·가격·메뉴·상호·채널명·구독 안내·진행 안내 등
              정보성 문구는 단순 등장만으로 경고하지 않는다.
            - 배경 글자를 편집자의 평가 문구로 해석하지 않는다.
              상호나 로고만으로 화면 속 인물·가게의 신원을 확정하지 않는다.
            - 배경·UI·인용이라는 형식이 모든 문제를 제외하는 이유는 아니다.
              원문에 구체적인 공격 표현이나 민감한 정보 노출이 있으면
              작성자의 동조 여부와 구별하여 검토할 수 있다.
            - 인용문과 그에 대한 비판·반박·동조를 구별한다.
              공격적인 인용을 비판하기 위해 보여주는 경우,
              인용된 주장을 제작자의 주장으로 처리하지 않는다.
            - 문구의 역할을 몰라도 표현 자체로 판단할 수 있으면 판단한다.
              출처나 역할이 결론을 바꾸는 필수 정보일 때만 그 부족함을 명시한다.

            ## 4. OCR 인식 오류를 다루는 방법
            - 읽히는 원문과 추정 복원을 분리한다.
              복원 가능성이 있으면 출력 계약이 허용하는 reading에만 기록한다.
            - 추정 복원 문구를 실제 원문 인용이나 검토 근거로 사용하지 않는다.
              STT·유명 사건·영상 제목·기억 속 표현으로 빈 글자를 채우지 않는다.
            - 일부 글자가 깨졌더라도 읽히는 부분만으로 검토 이유가 성립하면
              그 부분을 근거로 판단할 수 있다.
            - 빠진 글자나 복원 여부에 따라 정상 표현과 문제 표현이 갈리면
              UNCERTAIN으로 판단에 필요한 정보를 명시한다.
            - OCR 오류가 있다는 이유만으로 자동으로 UNCERTAIN을 선택하지 않는다.
              반대로 읽을 수 없는 문구를 PASS로 처리해 검토 완료처럼 설명하지 않는다.

            ## 5. 유형 선택
            먼저 구체적인 검토 이유가 있는지 판단하고 그다음 유형을 선택한다.
            유형에 해당하는 단어가 있다는 이유만으로 후보를 만들지 않는다.
            같은 문제에 여러 유형이 겹치면 가장 직접적인 유형을 선택한다.

            ### BELITTLEMENT — 대상 폄하
            - 포함: 사람·집단을 열등하거나 가치 없는 존재로 묘사하는 문구.
            - 제외: 대상의 행동·정책·결과물에 대한 비판 자체.
            - 경계: 비웃는 표현 방식이 핵심이면 MOCKERY,
              상품·서비스·작품 자체의 조롱이면 STRONG_NEGATIVE_REVIEW를 검토한다.

            ### MOCKERY — 조롱
            - 포함: 확인되는 사람·집단을 웃음거리로 만들거나
              능력·처지·외모 등을 낮춰 묘사하는 문구.
            - 제외: 유머 형식·장난스러운 어휘·타인의 경험을 상상한 설명 자체.
            - 경계: 웃음이나 악의를 상상하지 않고 실제 문구의 관계로 설명한다.

            ### STRONG_NEGATIVE_REVIEW — 조롱성 상품·서비스 평가
            - 포함: 상품·가게·서비스·작품을 단순 불만을 넘어
              모욕하거나 조롱하는 구체적인 표현.
            - 제외: 맛·가격·품질·기능에 대한 강한 불만이나 구매 비추천.
            - 경계: 평가가 사람·집단의 가치나 수준으로 넘어가면
              실제 평가 대상에 맞는 유형을 선택한다.

            ### GENERALIZATION — 부정적 일반화
            - 포함: 일부 사례를 근거로 집단 전체에 부정적인 특성을 부여하는 문구.
            - 제외: 범위를 명시한 관찰·통계·중립적인 집단 설명.
            - 경계: '전부' 같은 단어만 보지 않고 일반화한 속성과 대상을 확인한다.

            ### DISCRIMINATION — 차별
            - 포함: 성별·국적·인종·장애·나이 등의 특성을 근거로
              열등함을 주장하거나 불리한 대우·권리 제한을 정당화하는 문구.
            - 제외: 해당 특성의 단순 언급·정보 전달·차별을 비판하는 설명.
            - 경계: 집단의 비인간화·제거·폭력 선동은 HATE_SPEECH를 검토한다.

            ### HATE_SPEECH — 집단 혐오
            - 포함: 특정 집단을 비인간화하거나
              집단에 대한 제거·폭력·배제를 지지하는 문구.
            - 제외: 정책·조직·행동에 대한 비판이나 혐오 표현의 비판적 인용.
            - 경계: 부정적인 집단 평가를 모두 혐오 선동으로 확대하지 않는다.

            ### UNFAMILIAR_CONTEXT — 특수 맥락 표현
            - 포함: 제공된 자료에서 특수 의미와 현재 사용의 연결을 확인할 수 있고,
              그 의미 때문에 구체적인 검토 이유가 생기는 문구.
            - 제외: 낯선 단어·방언·밈·커뮤니티 관련 단어의 단순 등장.
            - 경계: 기억이나 사전 일치만으로 특수 의미를 확정하지 않는다.
              표현의 의미를 모른다는 사실과 위험하다는 판단을 구별한다.

            ### SENSITIVE_TOPIC — 민감한 소재의 문제적 취급
            - 포함: 피해·참사·질병 등 민감한 경험을 조롱하거나
              하찮게 취급·정당화하는 문구.
            - 제외: 보도·교육·추모·피해 경험 공유·단순 소재 언급.
            - 경계: 더 구체적인 공격·차별·폭력 유형이 있으면 그 유형을 우선한다.

            ### PRIVACY — 민감한 개인정보 노출
            - 포함: 식별 가능한 사람과 연결된 비공개 연락처·주소·계정 정보 등
              민감한 정보가 원문에서 확인되는 경우.
            - 제외: 공개 이름·공식 연락처·상호의 단순 등장.
            - 경계: 공개 여부·동의 여부를 지어내지 않는다.
              그 정보가 판단에 필수인데 확인할 수 없으면 부족한 정보를 명시한다.
              배경 글자라는 이유만으로 실제 노출 가능성을 제외하지 않는다.

            ### PROFANITY — 욕설·모욕적 비속어
            - 포함: 원문에서 확인되는 직접적인 욕설·모욕적 비속어.
            - 제외: 단어 설명·비판적 인용·OCR 추측으로만 만들어지는 욕설.
            - 경계: 대상과 사용 맥락을 고려하고,
              성적 모욕·집단 혐오 등 더 구체적인 유형이 있으면 우선한다.

            ### VIOLENCE — 위협·폭력 지지
            - 포함: 위해를 가하겠다는 위협이나 폭력을 권유·찬양·정당화하는 문구.
            - 제외: 사건 보도·교육·폭력 비판·관용적인 과장 표현.
            - 경계: 신체 훼손을 감각적 비유로 사용하는 경우는
              실제 위협과 구별하여 GRAPHIC_METAPHOR를 검토한다.

            ### GRAPHIC_METAPHOR — 구체적 신체 훼손 비유
            - 포함: 사람의 신체 훼손·섭취 등을 구체적으로 묘사하여
              음식·감각·상황에 빗대는 문구.
            - 제외: 신체 단어·가족 호칭 자체, 추억·정성의 맛 비유,
              식재료 설명·의료·교육·비판적 인용.
            - 경계: 공격 대상이 없어도 표현 자체의 근거로 검토할 수 있다.
              OCR 추정 복원으로 훼손 묘사를 만들어내지 않는다.

            ### SEXUAL — 성적 모욕·강압·노골적 묘사
            - 포함: 성적 모욕·대상화·강압을 지지하거나 노골적인 성행위를 묘사하는 문구.
            - 제외: 성교육·의료 정보·피해 경험 공유·성적인 소재의 단순 언급.
            - 경계: 확인되는 문구의 내용으로 판단한다.
              개인의 민감한 성적 정보를 드러내는 경우는 PRIVACY도 구별해 검토한다.

            ## 6. 설명과 반환
            - 실제 OCR 표현과 구체적인 검토 이유를 연결해 설명한다.
              문맥이 필요하면 판단에 사용한 OCR 연결을 명시한다.
            - '민감할 수 있다', '특정 맥락이 있다', '불쾌할 수 있다'만으로
              검토 이유를 대신하지 않는다.
            - 작성자의 악의나 논란 확률을 확정하지 않는다.
              화면 문구에 대한 관찰과 해석을 구별한다.
            - 판정은 공통 기준의 REVIEW_REQUIRED / PASS / UNCERTAIN을 따른다.
              출력 구조·필수 항목·null 처리·인용 방식은 뒤의 출력 계약을 따른다.
            """;

    private final OpenAiClient openAiClient;
    private ReviewGuidelineLibrary guidelineLibrary;
    @org.springframework.beans.factory.annotation.Autowired
    void setGuidelineLibrary(ReviewGuidelineLibrary library) { this.guidelineLibrary = library; }
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
                TimelineEventType.CAPTION, key(), SYSTEM_PROMPT, ALLOWED_CATEGORIES, 2, false, guidelineLibrary);
        lastResult.set(result);
        log.info("[{}] videoId={} status={} findings={}", key(), context.video().getId(),
                result.status(), result.findings().size());
        return result.findings();
    }

    @Override
    public java.util.Optional<String> consumeCoverageNotice(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        if (result == null) return java.util.Optional.empty();
        String notice = result.notice();
        String guidelineNotice = guidelineLibrary == null ? null : guidelineLibrary.unavailableNotice(TimelineEventType.CAPTION).orElse(null);
        return java.util.Optional.ofNullable(guidelineNotice == null ? notice : notice == null ? guidelineNotice : notice + " " + guidelineNotice);
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
