package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사전/정규식 기반 1차 탐지기.
 *
 * LLM 없이도 확실한 건 여기서 잡는다. 발언(STT)과 화면 자막(OCR) 양쪽이 공유한다.
 * 사전은 팀에서 계속 채워 넣으면 된다.
 */
@Component
public class RiskRuleEngine {

    private static final Map<RiskCategory, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(RiskCategory.PROFANITY,
                List.of("병신", "씨발", "시발", "좆", "새끼", "지랄", "미친놈", "꺼져"));
        KEYWORDS.put(RiskCategory.HATE_SPEECH,
                List.of("한남", "김치녀", "틀딱", "급식충", "맘충", "짱깨", "쪽바리", "흑형"));
        KEYWORDS.put(RiskCategory.DISCRIMINATION,
                List.of("여자들은 원래", "남자들은 원래", "장애인 같", "촌놈", "못배운", "여자가 무슨", "남자가 무슨"));
        KEYWORDS.put(RiskCategory.GENERALIZATION,
                List.of("다 그렇다", "다 똑같", "원래 다", "하나같이", "죄다"));
        KEYWORDS.put(RiskCategory.VIOLENCE,
                List.of("죽여버", "때려", "패버", "칼로", "밟아버"));
        KEYWORDS.put(RiskCategory.SEXUAL,
                List.of("야한", "벗고", "19금"));
        KEYWORDS.put(RiskCategory.ADVERTISING,
                List.of("광고 아니", "협찬 아니", "내돈내산", "제 돈 주고"));

        // 민감 주제는 단어 자체가 문제인 게 아니라, 다루는 순간 반응이 갈리는 영역이다.
        // 점수를 낮게 줘서 "확인해 보라"는 신호로만 쓴다. 판단은 LLM 분석기가 맡는다.
        // API 키가 없을 때의 안전망 역할이므로, 팀에서 계속 채워 넣으면 된다.
        KEYWORDS.put(RiskCategory.SENSITIVE_TOPIC,
                List.of("선거", "재선거", "대선", "총선", "보궐", "투표", "탄핵", "정당",
                        "여당", "야당", "좌파", "우파", "친일", "반일", "위안부",
                        "세월호", "이태원", "참사", "코로나", "백신",
                        "페미", "군대", "장애인", "성소수자", "난민"));
    }

    private static final List<PrivacyRule> PRIVACY_RULES = List.of(
            new PrivacyRule(Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}"), "전화번호"),
            new PrivacyRule(Pattern.compile("\\d{6}-?[1-4]\\d{6}"), "주민등록번호"),
            new PrivacyRule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"), "이메일 주소"),
            new PrivacyRule(Pattern.compile("\\d{2,3}-\\d{2,6}-\\d{2,6}"), "계좌번호로 보이는 숫자")
    );

    public List<Hit> detect(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }

        KEYWORDS.forEach((category, words) -> words.stream()
                .filter(text::contains)
                .findFirst()
                .ifPresent(word -> hits.add(new Hit(
                        category,
                        scoreOf(category),
                        "'" + word + "' 표현이 있습니다. " + category.getLabel()
                                + " 관점에서 확인해 보세요."
                ))));

        for (PrivacyRule rule : PRIVACY_RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                hits.add(new Hit(
                        RiskCategory.PRIVACY,
                        0.85,
                        rule.label() + "로 보이는 값(" + matcher.group() + ")이 나옵니다. "
                                + "공개해도 되는 정보인지 확인해 보세요."
                ));
                break; // 한 문장에서 개인정보는 한 건만 보고한다
            }
        }

        return hits;
    }

    private double scoreOf(RiskCategory category) {
        return switch (category) {
            case HATE_SPEECH, DISCRIMINATION -> 0.85;
            case VIOLENCE, PRIVACY -> 0.75;
            case PROFANITY, SEXUAL -> 0.55;
            // 룰만으로는 일반화인지 단순 관찰인지 구분할 수 없다. 약한 신호로만 남긴다.
            case GENERALIZATION -> 0.3;
            // 민감 주제는 그 자체로 잘못이 아니라 "검토 필요" 신호다. 낮게 잡는다.
            case SENSITIVE_TOPIC -> 0.35;
            default -> 0.35;
        };
    }

    public record Hit(RiskCategory category, double score, String reason) {}

    private record PrivacyRule(Pattern pattern, String label) {}
}
