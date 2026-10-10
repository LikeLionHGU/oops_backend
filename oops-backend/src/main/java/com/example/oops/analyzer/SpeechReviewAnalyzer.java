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
            # 발언 입력
            입력은 시간 정보가 붙은 음성 전사다. 실제 음성·영상·표정·화자 신원은 제공되지 않았다.
            짧은 전사 구간이 완결된 문장이나 화자 전환을 의미하지는 않는다.
            제공된 앞뒤 발언으로 문장을 해석하되, 빠진 단어를 만들어 원문에 추가하지 않는다.
            화면 글자가 함께 제공되더라도 화자가 직접 말한 내용으로 바꾸지 않는다.

            # 유형 선택 원칙
            공통 판단 기준으로 검토 필요 여부를 결정한 후 유형을 선택한다.
            유형에 해당하는 단어나 소재가 있다는 이유로 검토 후보를 만들지 않는다.
            같은 문제에 여러 유형이 겹치면 실제 검토 이유를 가장 직접적으로 설명하는 유형을 선택한다.
            서로 독립적인 문제의 반환 개수와 방식은 요청별 출력 계약을 따른다.

            # 유형 정의

            ## BELITTLEMENT — 사람·집단의 가치 저하
            포함: 사람·집단의 능력·존재·생활·선택을 열등하거나 하찮은 것으로 묘사한다.
            제외: 구체적인 행동·성과에 대한 비판, 상품 불만, 단순한 처지 설명.
            경계: 웃음거리로 만드는 비교·비유가 핵심이면 MOCKERY.
                  상품·서비스 자체의 평가라면 STRONG_NEGATIVE_REVIEW를 검토한다.

            ## MOCKERY — 대상의 처지·특성을 웃음거리로 만듦
            포함: 대상의 처지·특성·선택을 비웃는 비교·비유·반문 또는 연결된 흐름.
            제외: 상상적 이야기, 대체 행동 설명, 추억이나 비교 자체.
                  무엇을 어떻게 낮춰 비웃는지 원문으로 설명할 수 있어야 한다.
            경계: 사람·집단의 일반적인 가치 저하가 핵심이면 BELITTLEMENT.
                  상품·서비스 평가 속 조롱은 STRONG_NEGATIVE_REVIEW를 우선 검토한다.

            ## STRONG_NEGATIVE_REVIEW — 상품·서비스 평가 속 모욕적 가치 절하
            포함: 상품·서비스·가게·작품 평가가 문맥상 모욕이나 조롱으로 이어진다.
            제외: 불만, 낮은 평가, 구매 비추천, 강한 비판이나 단정적인 표현 자체.
                  리뷰와 구별되는 모욕·조롱의 근거가 필요하다.
            경계: 상품 평가를 넘어 이용자·주민·종사자를 낮추면 실제 사람·집단에 맞는 유형을 선택한다.

            ## GENERALIZATION — 부정적 속성의 집단 전체 확대
            포함: 일부 사례를 근거로 집단 전체에 부정적 속성이나 고정관념을 부여한다.
            제외: 범위를 한정한 관찰, 통계 설명, 중립적인 집단 특성 설명.
            경계: 특정 속성을 근거로 차별적 대우·배제를 주장하면 DISCRIMINATION.
                  집단의 비인간화·제거를 주장하면 HATE_SPEECH.

            ## DISCRIMINATION — 속성에 따른 열등함·차별적 대우
            포함: 성별·인종·국적·장애·나이 등 속성을 근거로 열등함을 부여하거나
                  권리·기회·대우의 제한을 정당화한다.
            제외: 속성의 단순 언급, 차별 경험 설명, 차별을 비판하는 인용.
            경계: 비인간화·집단 제거가 핵심이면 HATE_SPEECH.

            ## HATE_SPEECH — 집단에 대한 비인간화·혐오적 배제
            포함: 집단을 인간 이하로 묘사하거나 그 집단의 제거·폭력·혐오적 배제를 주장한다.
            제외: 집단 관련 정책·행동·사상에 대한 비판 자체, 혐오 표현을 비판하는 인용.
            경계: 단순 모욕이나 부정적 일반화를 모두 이 유형으로 확대하지 않는다.

            ## UNFAMILIAR_CONTEXT — 특수 의미가 현재 사용에 연결됨
            포함: 제공된 원문·문맥·확인 자료가 특정 사건·역사·커뮤니티의 특수 의미와
                  현재 표현의 사용을 연결하며, 그 의미에 구체적인 검토 이유가 있다.
            제외: 모르는 단어, 일반 방언, 의미가 불명확한 전사, 사전 항목과의 단순 일치.
            경계: 일반 모욕이나 신체 비유를 설명하지 못한다는 이유로 이 유형을 선택하지 않는다.

            ## SENSITIVE_TOPIC — 민감한 사건·피해를 다루는 방식
            포함: 재난·죽음·학대 등 사건이나 피해자의 고통을 웃음거리로 만들거나,
                  피해를 하찮게 취급·정당화하는 표현.
            제외: 주제 언급 자체, 보도·교육·추모·경험 공유, 신중한 비판.
            경계: 위협·차별·집단 공격 등 더 구체적인 유형으로 설명되면 해당 유형을 선택한다.
                  다른 유형을 고르기 어렵다는 이유로 사용하지 않는다.

            ## PRIVACY — 타인의 사적 정보 노출
            포함: 타인의 사적 연락처·상세 거주지·건강·성생활 등
                  식별 가능한 사적 정보를 공개하는 표현.
            제외: 공개된 이름·직함·공식 연락처의 단순 언급, 식별 불가능한 일반 설명.
            경계: 공개 여부나 대상 식별이 판단에 필수인데 확인되지 않으면
                  비공개 정보라고 확정하지 말고 부족한 정보를 기록한다.

            ## MISINFORMATION — 근거 있는 사실 정확성 문제
            포함: 사실처럼 제시된 주장이 제공된 확인 자료와 충돌하거나,
                  원문 안에 직접 확인할 수 있는 모순이 있어 정확성 검토가 필요하다.
            제외: 의견·취향·명시적인 추측·농담, 사실 주장이라는 이유만으로 생기는 의심.
            경계: 외부 자료가 필요한 이름·날짜·수치 확인은 별도 사실 대조 기능의 역할이다.
                  검색 없이 기억으로 거짓이라고 판정하거나, 모든 미검증 주장을 경고하지 않는다.

            ## PROFANITY — 맥락상 검토할 욕설·비속어
            포함: 대상을 공격하는 욕설이나 공개 전 표현 수위를 검토할 구체적인 비속어 사용.
            제외: 단어 설명, 비판적 인용, 전사 오류로만 추정되는 욕설.
            경계: 욕설의 존재보다 차별·위협·성적 모욕이 핵심이면 해당 유형을 선택한다.

            ## VIOLENCE — 실제 폭력의 위협·권유·정당화
            포함: 타인에게 폭력을 가하겠다는 위협, 폭력 실행의 권유·찬양·정당화.
            제외: 사건 보도·교육·비판, 실제 폭력과 연결되지 않는 관용 표현이나 감각 비유.
            경계: 실제 행위·위협이 아니라 구체적인 신체 훼손 이미지의 비유라면 GRAPHIC_METAPHOR.

            ## GRAPHIC_METAPHOR — 구체적인 신체 훼손·섭취 비유
            포함: 감각·상황을 사람의 신체를 뜯거나 훼손·섭취하는 구체적인 이미지에 빗댄 표현.
            제외: 신체 단어·가족 호칭 자체, 관용 표현, 식재료 설명,
                  의료·교육 맥락의 실제 행위 설명.
            경계: 공격 대상 없이 표현 자체를 검토할 수 있다.
                  실제 피해자·폭력 의도는 만들지 말고, 실제 위협이면 VIOLENCE로 구분한다.

            ## SEXUAL — 성적 모욕·대상화·강압적 표현
            포함: 타인의 신체·성생활을 성적으로 모욕하거나 대상화하는 표현,
                  원치 않는 성적 행위의 위협·강요·정당화, 노골적인 성행위 묘사.
            제외: 성교육·의료·건강 정보, 피해 경험 공유, 성적 소재의 단순 언급.
            경계: 사적 정보 노출이 핵심이면 PRIVACY도 구분해 검토한다.
                  성적 단어만으로 대상화·강압을 추정하지 않는다.
            """;

    private final OpenAiClient openAiClient;
    private final boolean dialogueEnabled;
    private final boolean candidateReviewEnabled;
    private final int maxCandidates;
    private VisualContextReviewer visualReviewer;
    private ReviewCaseLibrary caseLibrary;
    private ReviewGuidelineLibrary guidelineLibrary;
    @org.springframework.beans.factory.annotation.Autowired
    void setGuidelineLibrary(ReviewGuidelineLibrary library) { this.guidelineLibrary = library; }
    public SpeechReviewAnalyzer(OpenAiClient client) { this(client, true); }
    /** Legacy constructor retained for existing contract regression tests and baseline comparisons. */
    public SpeechReviewAnalyzer(OpenAiClient client, boolean enabled) {
        this(client, enabled, false, 24);
    }
    public SpeechReviewAnalyzer(OpenAiClient client,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.dialogue-review-enabled:true}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-enabled:true}") boolean candidateEnabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-max-candidates:24}") int maxCandidates) {
        if (maxCandidates < 1 || maxCandidates > 200) throw new IllegalArgumentException("Candidate budget must be 1..200");
        this.openAiClient = client;
        this.dialogueEnabled = enabled;
        this.candidateReviewEnabled = candidateEnabled;
        this.maxCandidates = maxCandidates;
    }
    public SpeechReviewAnalyzer(OpenAiClient client,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.dialogue-review-enabled:true}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-enabled:true}") boolean candidateEnabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-max-candidates:24}") int maxCandidates,
            VisualContextReviewer visualReviewer) {
        this(client, enabled, candidateEnabled, maxCandidates);
        this.visualReviewer = visualReviewer;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SpeechReviewAnalyzer(OpenAiClient client,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.dialogue-review-enabled:true}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-enabled:true}") boolean candidateEnabled,
            @org.springframework.beans.factory.annotation.Value("${oops.analysis.candidate-review-max-candidates:24}") int maxCandidates,
            VisualContextReviewer visualReviewer, ReviewCaseLibrary caseLibrary) {
        this(client, enabled, candidateEnabled, maxCandidates, visualReviewer);
        this.caseLibrary = caseLibrary;
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
        TextReviewEngine.Result result = candidateReviewEnabled ? CandidateReviewEngine.run(openAiClient, context, maxCandidates, visualReviewer, caseLibrary, guidelineLibrary)
                : TextReviewEngine.run(openAiClient, context,
                TimelineEventType.SPEECH, key(), SYSTEM_PROMPT + "\n" + ContextualComparisonPolicy.PROMPT
                    + (guidelineLibrary == null ? "" : guidelineLibrary.select(TimelineEventType.SPEECH,
                        context.reviewInput().segments(), "legacy-speech-review").prompt()),
                ALLOWED_CATEGORIES, 3, dialogueEnabled);
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
        String guidelineNotice = guidelineLibrary == null ? null : guidelineLibrary.unavailableNotice(TimelineEventType.SPEECH).orElse(null);
        return java.util.Optional.ofNullable(guidelineNotice == null ? notice : notice == null ? guidelineNotice : notice + " " + guidelineNotice);
    }

    @Override
    public java.util.Optional<TextReviewEngine.Result> consumeReviewResult(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        lastResult.remove();
        return java.util.Optional.ofNullable(result);
    }

    static final java.util.Set<RiskCategory> ALLOWED_CATEGORIES = java.util.EnumSet.of(
            RiskCategory.UNFAMILIAR_CONTEXT, RiskCategory.BELITTLEMENT, RiskCategory.MOCKERY,
            RiskCategory.GENERALIZATION, RiskCategory.SENSITIVE_TOPIC, RiskCategory.DISCRIMINATION,
            RiskCategory.HATE_SPEECH, RiskCategory.PRIVACY, RiskCategory.MISINFORMATION,
            RiskCategory.PROFANITY, RiskCategory.VIOLENCE, RiskCategory.SEXUAL,
            RiskCategory.STRONG_NEGATIVE_REVIEW, RiskCategory.GRAPHIC_METAPHOR);

}
