package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 발언(STT 대본) 리스크 분석기.
 *
 * 보는 것은 다섯 가지다. 사회·정치 맥락 / 집단 일반화 / 커뮤니티 맥락 /
 * 소개 대상 평가 / 속성을 근거로 한 차별.
 * 키워드로는 못 잡는 유형이라 LLM 판정을 쓴다. 대본을 통째로 넣지 않고
 * 창(window) 단위로 잘라 넣는데, 앞뒤 문맥이 있어야 판단이 되기 때문이다.
 *
 * **범위를 한 번 좁혔다가 일부를 되살렸다. 그 기록을 남긴다.**
 * 39분짜리 대화 영상에서 후보 134건이 나왔고 대부분 오탐이었다.
 * '순대' 가 커뮤니티 은어로 14번, '너' 가 집단 일반화로 4번 올라왔다.
 * 그래서 유형을 셋으로 줄였는데, 그러자 "할머니 맛" 이나 소개 중인 가게의
 * 메뉴 평가처럼 **원래 잡아야 했던 것들이 함께 사라졌다.**
 *
 * 오탐의 진짜 원인은 카테고리가 넓은 것이 아니었다.
 * "애매해도 반드시 올려라" 와 창당 건수 무제한이었다.
 * 그 둘을 막은 뒤에 유형을 다시 늘렸다.
 * **범위를 줄이는 것과 관문을 세우는 것은 다르다.** 세워야 할 것은 관문이다.
 *
 * 넓게 잡는 것이 안전해 보이지만 그렇지 않다. 오탐 하나가 나머지 카드의
 * 신뢰를 같이 깎는다. 반대로 좁게 잡으면 정작 봐야 할 것이 안 나온다.
 * 카드 수가 아니라 **한 장 한 장이 이름을 댈 수 있는지** 를 기준으로 삼는다.
 *
 * **사실 확인은 여기서 하지 않는다.**
 * 이 분석기는 검색을 하지 않아서 근거 자료를 붙일 수 없다.
 * 근거 없이 "이건 틀린 것 같다" 고 말하는 게 이 도구가 가장 하면 안 되는 일이다.
 * 연도·숫자·이름 확인은 EntityCheckAnalyzer 가 실제로 기사를 찾아서 대조한다.
 * 역할을 이렇게 나눠 두면 FACT_CHECK 카드에는 항상 참고 자료가 붙는다.
 *
 * API 키가 없으면 조용히 빈 결과를 돌려주고, 룰 기반 SubtitleAnalyzer 결과만 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeechReviewAnalyzer implements ContentAnalyzer {

    /**
     * 한 번에 LLM 에 넣는 대본 줄 수.
     * 한 번에 너무 많이 주면 모델이 눈에 띄는 몇 개만 보고 나머지를 흘린다.
     * 반대로 너무 잘게 쪼개면 호출이 늘어 OpenAI 요청 한도에 걸린다.
     * 20줄이 그 사이의 타협점이다.
     */
    private static final int WINDOW_SIZE = 20;
    /** 창 사이에 겹치는 줄 수. 경계에서 문맥이 끊기는 걸 막는다. */
    private static final int OVERLAP = 3;

    /**
     * 이 분석기가 만들 수 있는 유형. 이 다섯 말고는 버린다.
     *
     * 프롬프트에 "다섯만 보라" 고 적어도 모델은 종종 MOCKERY, PROFANITY 를 섞어 보낸다.
     * 프롬프트는 부탁이고, 여기가 문이다.
     *
     * **BELITTLEMENT 와 DISCRIMINATION 은 뺐다가 되살렸다.**
     * 오탐 134건을 줄이려고 2026-08-21 에 이 둘을 지웠는데, 그러자
     * "할머니 맛", 소개 중인 가게의 메뉴 평가, 국적을 근거로 한 발언이
     * 통째로 안 잡혔다. 발언에서 이 둘을 만들 수 있는 분석기가 여기뿐이라
     * 지운 순간 아무도 안 맡는 상태가 됐다. 룰 사전에는 해당 항목이 없다.
     * '소개 중인 대상에 대한 평가' 는 README 가 말하는 확인 지점 세 축 중 하나다.
     * 되살리는 대신 두 유형에도 "이름을 댈 수 있는가" 관문을 세웠다.
     *
     * **MOCKERY 는 일부러 뺀 채로 뒀다.** 자학과 농담을 조롱으로 올리는 일이 잦았고,
     * 정말 봐야 할 것은 대부분 BELITTLEMENT 로도 잡힌다.
     * 병합 단계에서 둘은 같은 PUTDOWN 그룹이라 카드로는 어차피 한 장이 된다.
     */
    static final java.util.Set<RiskCategory> ALLOWED = java.util.Set.of(
            RiskCategory.SENSITIVE_TOPIC,
            RiskCategory.GENERALIZATION,
            RiskCategory.UNFAMILIAR_CONTEXT,
            RiskCategory.BELITTLEMENT,
            RiskCategory.DISCRIMINATION);

    /**
     * 한 창(20줄)에서 받아들일 최대 건수.
     *
     * 39분짜리 영상에서 창 75개가 135건을 만든 적이 있다. 대부분 오탐이었다.
     * 20줄은 대략 40초 분량이고, 그 안에 정말로 다시 볼 지점이 셋 이상 있는 일은 드물다.
     * 모델이 셋 이상 보내면 점수 높은 둘만 남긴다.
     */
    static final int MAX_PER_WINDOW = 2;

    /**
     * 이런 target 은 집단도 은어도 아니다. 무조건 버린다.
     *
     * 전부 실제 로그에서 나온 것들이다.
     * GENERALIZATION/'너' 가 한 영상에서 네 번 나왔다. '너' 는 집단이 아니다.
     * 대화체 영상에서 이런 말은 수백 번 나오므로, 하나만 새도 목록이 통째로 무너진다.
     *
     * 여기에 '남자', '여자', '20대' 를 넣지 마라. 그건 진짜 집단이고,
     * 성질이 붙었는지는 프롬프트가 판단할 몫이다.
     */
    static final java.util.Set<String> GENERIC_TARGETS = java.util.Set.of(
            "너", "나", "저", "우리", "얘", "걔", "쟤", "그", "이", "저것",
            "너네", "니가", "내가", "자기", "본인", "사람", "사람들",
            "친구", "친구들", "그 사람", "이 사람", "그거", "이거", "저거",
            "표현", "말", "얘기", "이야기", "상황", "부분", "내용");

    private static final String SYSTEM_PROMPT = """
            너는 영상을 공개하기 전에 제작팀이 다시 확인할 지점을 짚어주는 검수 보조자다.

            중요한 원칙: 너는 판정하지 않는다.
            "이 발언은 부적절하다", "논란 가능성 85%" 같은 말은 하지 마라.
            제작자는 이미 영상을 수십 번 봤고, 알면서도 넣은 장면이 있을 수 있다.
            네 역할은 옳고 그름을 정하는 것이 아니라,
            **제작자가 다시 볼 만한 지점과 그 이유를 알려주는 것**이다.

            ## 네가 보는 것은 다섯 가지다

            아래 다섯에 해당하지 않으면 **아무것도 올리지 마라.**
            욕설·선정성·사실 오류는 다른 분석기가 맡는다.
            네가 겹쳐서 올리면 목록만 길어지고 정작 봐야 할 것이 묻힌다.

              ① 사회·정치 맥락        → SENSITIVE_TOPIC
              ② 집단 일반화           → GENERALIZATION
              ③ 커뮤니티 맥락         → UNFAMILIAR_CONTEXT
              ④ 소개 대상 평가        → BELITTLEMENT
              ⑤ 속성을 근거로 한 차별 → DISCRIMINATION

            이 다섯을 고른 이유는 하나다. **제작자가 스스로는 못 알아채는 것들이다.**
            욕설은 들으면 안다. 커뮤니티 은어는 모르면 끝까지 모른다.
            소개 중인 가게에 흘린 한마디는 말한 본인이 기억조차 못 한다.

            **다섯 모두 관문이 있다.** 관문은 하나같이 "이름을 댈 수 있는가" 를 묻는다.
            이름을 못 대는 항목은 제작자가 열어봐도 확인할 것이 없다. 그러면 빼라.

            ---

            ① 사회·정치 맥락 → SENSITIVE_TOPIC

            역사적 사건, 사회적 참사, 재난, 차별 사건, 정치적 대립과 얽힌 표현.
            지금 시점에서 원래 의도와 다르게 읽힐 수 있는 풍자.

            **관문: 어떤 사건·맥락인지 이름을 댈 수 있는가?**
            "세월호", "5·18", "일제강점기 징용", "코로나 초기에 특정 집단을 지목한 일"
            처럼 적을 수 있어야 한다. 이름을 못 대면 그 항목은 빼라.

            정책에 대한 의견, 지지 표명, 정치인 실명 언급 자체는 대상이 아니다.
            **좌우를 가리지 말고 같은 기준을 적용해라.** 한쪽만 잡으면 그게 편향이다.

            ---

            ② 집단 일반화 → GENERALIZATION

            **세 가지가 한 문장 안에 전부 있어야 한다. 하나라도 없으면 빼라.**

              (1) 집단을 가리키는 말   여자, 남자, 20대, 경상도 사람, 공무원, 중국인
              (2) 전체로 묶는 표현     다, 죄다, 원래, 역시, 다들, 하나같이, 믿고 거른다
              (3) 그 집단에 붙인 성질  감정적이다, 책임감이 없다, 시끄럽다

            걸리는 예:
              "요즘 20대는 다 책임감이 없어"    (1)(2)(3) 전부 있다
              "역시 OO 사람들은 시끄러워"       (1)(2)(3) 전부 있다

            **올리지 마라. 아래는 전부 실제로 잘못 잡았던 것들이다.**
              개인을 가리키는 말   너, 얘, 걔, 친구, 그 사람, 사람 이름  ← 집단이 아니다
              나이·연도 언급      07년생, 스무 살, 2007년생            ← 성질을 안 붙였다
              집단은 나오지만 안 묶음  "남자 입장에서는", "여자들끼리 대화할 때"
              사실 서술          "20대 투표율이 낮습니다"
              개인 경험          "제 친구는 책임감이 없어요"

            "남자들은" 이 나왔다고 걸리는 게 아니다.
            **그 뒤에 집단 전체를 규정하는 성질이 붙어야 걸린다.**
            성질이 안 붙었으면 그냥 대화다.

            ---

            ③ 커뮤니티 맥락 → UNFAMILIAR_CONTEXT

            겉보기엔 평범한 말이 특정 온라인 커뮤니티에서 별도의 뜻으로 굳어진 경우.
            어미와 말버릇도 포함한다.

            **관문: 그 커뮤니티나 사건의 이름을 댈 수 있는가?**
            "특정 커뮤니티 말투로 알려진 어미", "OO 사건 이후 조롱하는 뜻이 된 단어"
            처럼 적을 수 있어야 한다.
            "어떤 맥락에서 다르게 쓰일 수도 있다" 는 이름이 아니다. 그러면 빼라.

            걸리는 예:
              "~노", "~노?", "이기야"   표준어 문장 끝에 붙는 특정 커뮤니티 어미
                                       (사투리 어휘가 함께 나오면 사투리로 보고 넘어간다)
              정치인·정당·지지층을 가리키는 은어나 낙인 표현

            **올리지 마라. 아래는 전부 실제로 잘못 잡았던 것들이다.**
              음식·음료·물건 이름   순대, 맥주, 소주, 랍스터, 코코아 파우더
              호칭과 친족어         오빠, 형님, 언니, 아저씨, 아빠, 선배님
              널리 쓰이는 감탄·유행어  미쳤다, 대박, 에바, 인싸, 알잘딱깔센
              지명·상호·사람 이름 자체
              그냥 낯설게 들리는 단어

            **낯선 것과 맥락이 있는 것은 다르다.**
            네가 모르는 단어라고 커뮤니티 은어인 것이 아니다.
            근거 없이 올리면 제작자는 열어보고 아무것도 없는 카드를 만난다.
            그런 카드가 몇 개 쌓이면 나머지도 안 보게 된다.

            ---

            ④ 소개 대상 평가 → BELITTLEMENT

            영상에서 소개·방문·시식·언급 중인 **실재하는 대상**을 깎아내리는 대목.
            가게, 메뉴, 음식의 맛, 제품, 작품, 브랜드, 실명 인물이 대상이다.

            말한 본인은 지나가듯 흘린 말이라 기억조차 못 하는 경우가 많은데,
            당사자는 그 장면만 잘라서 본다. 그래서 제작자가 놓치는 자리다.

            **관문: 평가받은 대상의 이름을 댈 수 있는가?**
            "OO식당의 김치찌개", "그 가게 사장님", "OO 브랜드 버거" 처럼
            영상에 실제로 등장하는 대상을 적을 수 있어야 한다.
            적을 수 없으면 그 항목은 빼라.

            걸리는 예:
              "너무 특색이 없어가지고"        소개 중인 가게 메뉴에 대한 평가
              "할머니 살을 뜯는 것 같은 맛"   지금 먹고 있는 음식에 대한 평가
              "돈이 아깝다", "여기 왜 오지"   방문 중인 가게에 대한 평가

            **올리지 마라. 아래는 전부 실제로 잘못 잡았던 것들이다.**
              화자 자신에 대한 말   "나 못해", "공부나 해야겠다", "내가 바보지"
              화자의 소속·작품     자기 채널·자기 영상에 대한 자평
              대상이 대명사뿐      "저거 좀 아니다"  ← 무엇인지 적을 수 없다
              칭찬·중립 서술       "맛있다", "양이 많네요", "가격은 8000원입니다"
              관용구로 쓰인 상호명  "OO 같은 소리 하고 있어"
                                   ← 상호를 빌린 관용구다. 그 브랜드 평가가 아니다.
                                     다만 그 브랜드의 음식·서비스를 실제로
                                     평가했다면 그건 올려라.

            **자기 얘기와 대상 평가를 헷갈리지 마라.**
            화자가 자신을 낮추는 말은 여기 해당하지 않는다.
            이걸 흘려서 "못해", "공부나 해야겠다" 가 비하 카드로 나간 적이 있다.

            ---

            ⑤ 속성을 근거로 한 차별 → DISCRIMINATION

            성별·국적·인종·장애·나이·출신 지역을 **근거로 삼아**
            대상을 낮추거나 자격을 부정하는 표현.

            **관문: 어떤 속성을 근거로 삼았는지 이름을 댈 수 있는가?**
            "국적을 품질 판단의 근거로 삼았다", "장애를 비하 비유로 썼다" 처럼
            적을 수 있어야 한다. 적을 수 없으면 빼라.

            걸리는 예:
              "여자가 무슨 운전을"      성별을 자격 부정의 근거로 삼았다
              "중국산이니까 그렇지"     국적을 품질 판단의 근거로 삼았다
              "장애인 같이 왜 그래"     장애를 비하 비유로 썼다

            **올리지 마라.**
              속성 언급 자체   "중국에서 만든 제품입니다", "여성 참가자가 많네요"
              사실 서술       "이 공장은 중국에 있습니다"
              집단 전체를 묶은 말  그건 ② 다. 아래를 봐라.

            ② 와 겹치면 **하나만 올려라.**
            집단 전체를 하나로 묶었으면 ②, 개별 대상을 속성 때문에 낮췄으면 ⑤ 다.

            ---


            ## 몇 개나 올릴 것인가

            주어진 20줄에 위 다섯이 **하나도 없는 경우가 정상이다.**
            그럴 때는 {"findings":[]} 로 답해라. 억지로 채우지 마라.

            한 창에서 **최대 2건**까지만 올린다.
            셋 이상 보이면 가장 확실한 둘만 남겨라.

            같은 표현이 여러 줄에 반복되면 **한 번만** 올려라.

            ## 사실 관계는 네 몫이 아니다

            연도가 맞는지, 숫자가 맞는지, 이름이 맞는지는 다른 분석기가
            실제로 자료를 검색해서 대조한다. 너는 검색을 하지 않으므로
            "이건 사실이 아닐 수 있다" 고 말할 근거가 없다.
            사실이 의심스러운 대목을 보더라도 올리지 마라.

            ## 판단 절차 (반드시 이 순서로)

            1. 이 말이 향하는 대상이 누구/무엇인지 정한다.
            2. 대상이 없거나, 화자 자신이거나, 관용 표현이면 넘어간다.
               고유명사가 나왔다고 그 대상을 문제 삼은 것이 아니다.
               ("OO 같은 소리 하고 있어" 는 상호를 빌린 관용구다.
                다만 그 브랜드의 음식·서비스를 실제로 평가했다면 ④ 로 올려라.)
            3. ① ~ ⑤ 중 어디에 해당하는지 고른다. 다섯 다 아니면 넘어간다.
            4. 그 유형의 **관문을 통과하는지** 확인한다. 못 하면 넘어간다.
            5. 남는 것에 대해 "왜 다시 봐야 하는지" 를 한 문장으로 적는다.

            넘어가야 할 것:
            - 사실 관찰, 경향 서술
            - 화자가 자기 자신에 대해 하는 이야기
            - 상황 설명, 진행 멘트
            - 대상이 특정되지 않는 일반적인 감상
            - 일상 대화, 잡담, 근황 이야기
              (음식·가게 이야기 자체는 넘기되, 소개 중인 대상을
               평가했다면 그건 ④ 다. 음식 이야기라고 뭉개지 마라.)
            - **연도·숫자·이름이 틀린 것 같은 대목** (사실 확인 분석기가 맡는다)

            ## reason 작성 규칙

            - 단정하지 마라. "부적절하다", "문제가 있다" 라고 쓰지 마라.
            - 무엇 때문에 다시 봐야 하는지를 사실로 적어라.
            - 어떤 맥락인지 **이름을 대라.** 뭉뚱그리면 제작자가 확인할 수가 없다.

            reason 은 사실을 서술하는 한 문장으로 끝낸다.
            "확인해 보세요", "다시 보세요" 같은 말은 붙이지 마라.
            그 안내는 결과 화면에 한 번만 나가므로 매 항목마다 반복하면 지저분해진다.

            좋은 예:
            - "특정 세대 전체를 하나의 성질로 묶는 표현입니다."
            - "이 어미는 특정 온라인 커뮤니티 말투로 알려져 있습니다."
            - "과거 사회적 참사와 연결해 읽힐 수 있는 표현입니다."
            - "소개 중인 가게의 메뉴를 평가하는 대목입니다. 당사자가 볼 수 있습니다."
            - "국적을 품질 판단의 근거로 삼은 표현입니다."

            나쁜 예:
            - "부적절한 발언입니다"
            - "논란이 될 가능성이 높습니다"
            - "특정 세대나 문화적 맥락에서 사용될 수 있는 표현입니다"  ← 아무 정보가 없다
            - "특정한 상황에서 문제가 될 수 있습니다"                 ← 무엇이 문제인지 없다

            **무엇이 어떤 맥락인지 이름을 대지 못하겠으면 그 항목은 올리지 마라.**
            뭉뚱그린 문장은 제작자가 확인할 수가 없어서 없느니만 못하다.

            ## 출력 형식

            반드시 이 JSON 형식으로만 답한다:
            {"findings":[{"index":0,"target":"이 발언이 향하는 대상","category":"UNFAMILIAR_CONTEXT","score":0.6,"reason":"왜 다시 확인해야 하는지 한 문장","context":"관련된 배경이나 사례가 있으면 한 문장. 없으면 생략"}]}

            category 는 SENSITIVE_TOPIC, GENERALIZATION, UNFAMILIAR_CONTEXT,
            BELITTLEMENT, DISCRIMINATION 다섯 중 하나다. 다른 값은 쓰지 마라.

            index 는 대본 줄 번호다.
            score 는 확인 우선순위다. 관문을 확실히 통과하면 0.7 이상, 그 외 0.5.
            **확신이 없으면 빼라.** 낮은 점수로 올려서 채우지 마라.
            target 은 **한 단어에서 세 단어 이내**로 짧게 적어라.
            문장을 그대로 옮기지 마라. "할머니의 살을 뜯는 거 같다" 가 아니라 "할머니" 로 적는다.
            같은 대상에 대한 지적을 하나로 묶는 데 쓰기 때문이다.
            target 을 짧게 못 적겠으면 그 줄은 빼라.
            """;

    private final OpenAiClient openAiClient;

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
        return context.hasTranscript() && openAiClient.isEnabled();
    }

    /**
     * 이 분석기만 길이에 비례한다.
     *
     * 대본을 20줄씩 잘라 창마다 한 번씩 부르므로,
     * 대본이 두 배면 호출도 두 배다. 다른 분석기는 상한이 걸려 있다.
     */
    @Override
    public boolean scalesWithLength() {
        return true;
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        List<TranscriptSegment> transcript = context.transcript();
        List<RiskFinding> findings = new ArrayList<>();

        for (int start = 0; start < transcript.size(); start += WINDOW_SIZE - OVERLAP) {
            int end = Math.min(start + WINDOW_SIZE, transcript.size());
            List<TranscriptSegment> window = transcript.subList(start, end);

            findings.addAll(analyzeWindow(context, window, start));

            if (end == transcript.size()) break;
        }

        // 창이 겹치는 구간에서 같은 발언이 두 번 잡힐 수 있어 여기서 한 번 걸러준다
        List<RiskFinding> deduped = dedupe(findings);
        log.info("[speech-risk] videoId={} 창={}개 findings={} (중복제거 후 {})",
                context.video().getId(),
                (transcript.size() / Math.max(1, WINDOW_SIZE - OVERLAP)) + 1,
                findings.size(), deduped.size());
        return deduped;
    }

    private List<RiskFinding> analyzeWindow(AnalysisContext context,
                                            List<TranscriptSegment> window,
                                            int offset) {
        String userPrompt = buildPrompt(window);

        LlmResult result = openAiClient
                .completeAsJson(SYSTEM_PROMPT, userPrompt, LlmResult.class)
                .orElse(null);

        if (result == null || result.findings() == null) {
            return List.of();
        }

        List<RiskFinding> findings = new ArrayList<>();
        for (LlmFinding item : result.findings()) {
            int localIndex = item.index() == null ? -1 : item.index();
            if (localIndex < 0 || localIndex >= window.size()) {
                continue; // LLM 이 엉뚱한 번호를 준 경우 버린다
            }

            RiskCategory category = RiskCategory.fromOrDefault(
                    item.category(), RiskCategory.SENSITIVE_TOPIC);

            // 사실 확인 후보는 여기서 만들 수 없다.
            //
            // 프롬프트로 막아뒀지만 모델이 그래도 FACT_ERROR 를 뱉을 때가 있다.
            // 그게 통과하면 candidateType 이 FACT_CHECK 로 붙는데,
            // 이 분석기는 검색을 하지 않으므로 참고 자료가 하나도 없다.
            // 사용자에게는 "사실 확인" 카드인데 확인할 자료가 없는 상태로 보인다.
            // 근거 없는 사실 주장은 이 도구가 하지 말아야 할 바로 그것이다.
            if (CandidateType.from(category) == CandidateType.FACT_CHECK) {
                log.debug("[speech-risk] 사실 확인은 entity-check 몫이라 버립니다 — {} / {}",
                        category, item.reason());
                continue;
            }

            // 다섯 가지 영역 밖은 여기서 끊는다.
            // 욕설·선정성은 룰 기반 SubtitleAnalyzer 가, 사실 확인은 entity-check 가 맡는다.
            // MOCKERY 는 일부러 범위 밖에 뒀다. ALLOWED 주석을 봐라.
            // 여기에 무엇을 더 빼려면 먼저 '그럼 누가 맡는가' 를 확인해라.
            // 지난번에 BELITTLEMENT 를 빼면서 아무도 안 맡는 상태를 만들었다.
            if (!ALLOWED.contains(category)) {
                log.debug("[speech-risk] 범위 밖이라 버립니다 — {} / {}", category, item.reason());
                continue;
            }

            TranscriptSegment segment = window.get(localIndex);
            double score = item.score() == null ? 0.5 : Math.max(0.0, Math.min(1.0, item.score()));

            // 대상을 못 적었다면 모델이 근거 없이 올린 것이다. 버린다.
            if (item.target() == null || item.target().isBlank()) {
                continue;
            }

            // 대명사와 지시어는 집단도 은어도 아니다.
            if (GENERIC_TARGETS.contains(item.target().trim())) {
                log.debug("[speech-risk] 대상이 너무 일반적이라 버립니다 — '{}' / {}",
                        item.target(), item.reason());
                continue;
            }

            String reason = item.reason() == null ? "확인이 필요한 대목입니다." : item.reason();

            // 배경 설명이 있으면 붙인다. 제작자가 판단할 재료가 된다.
            if (item.context() != null && !item.context().isBlank()) {
                reason = reason + " 참고: " + item.context();
            }

            // 알맹이 없는 사유는 버린다. 제작자가 확인할 수가 없다.
            if (!VagueReasonFilter.isUseful(reason)) {
                continue;
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
                    .target(item.target())
                    .build());
        }

        // 한 창에서 너무 많이 올라오면 신뢰할 수 없는 결과다. 점수 높은 순으로 자른다.
        if (findings.size() > MAX_PER_WINDOW) {
            log.debug("[speech-risk] 한 창에서 {}건이 올라와 상위 {}건만 남깁니다",
                    findings.size(), MAX_PER_WINDOW);
            findings.sort(java.util.Comparator.comparingDouble(RiskFinding::getScore).reversed());
            findings = new ArrayList<>(findings.subList(0, MAX_PER_WINDOW));
        }
        return findings;
    }

    private String buildPrompt(List<TranscriptSegment> window) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < window.size(); i++) {
            TranscriptSegment s = window.get(i);
            lines.append("[%d] (%s) %s%n".formatted(i, formatTime(s.getStartMs()), s.getText()));
        }

        return "다음은 영상 대본이다.\n"
                + "사회·정치 맥락, 집단 일반화, 커뮤니티 맥락,\n"
                + "소개 대상 평가, 속성을 근거로 한 차별 다섯 중 하나에\n"
                + "확실히 해당하는 줄만 찾아라. 없으면 빈 배열로 답한다.\n\n" + lines;
    }

    /** 같은 (시작시각, 카테고리) 는 한 건으로 본다. 점수가 높은 쪽을 남긴다. */
    private List<RiskFinding> dedupe(List<RiskFinding> findings) {
        Map<String, RiskFinding> best = new HashMap<>();
        for (RiskFinding f : findings) {
            String key = f.getStartMs() + "|" + f.getCategory();
            RiskFinding existing = best.get(key);
            if (existing == null || f.getScore() > existing.getScore()) {
                best.put(key, f);
            }
        }
        return new ArrayList<>(best.values());
    }

    static String formatTime(long ms) {
        long totalSec = ms / 1000;
        return "%02d:%02d".formatted(totalSec / 60, totalSec % 60);
    }

    // ----- LLM 응답 매핑 -----
    record LlmResult(List<LlmFinding> findings) {}

    record LlmFinding(Integer index, String target, String category, Double score,
                      String reason, String context) {}
}
