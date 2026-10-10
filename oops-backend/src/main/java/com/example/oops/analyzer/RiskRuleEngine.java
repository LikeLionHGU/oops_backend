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

    /**
     * 욕처럼 보이지만 욕이 아닌 단어. 여기 걸리면 그 위치의 욕설 매칭을 무시한다.
     * "시발점", "개나리" 처럼 소리만 같은 단어가 카드가 되는 걸 막는다.
     */
    private static final List<String> PROFANITY_EXCEPTIONS = List.of(
            "시발점", "시발역", "시발택시", "좆밥이 아니", "지랄탄");

    static {
        // 2026-10 고도화: 맥락 검토 8유형으로만 낸다.
        //
        // 예전에는 여기서 민감 주제("선거", "코로나", "군대"…), 일반화("죄다"), 폭력("때려"),
        // 선정("19금"), 광고("내돈내산") 단어까지 잡았다. 단어가 나왔다는 것만으로는
        // 논란이 아니고("군대 다녀왔어요"), 8유형에도 없는 분류라 뺐다. 판단은 맥락 검토가 한다.
        // 멸칭("한남", "틀딱"…)도 뺐다. 맥락 사전이 일반 용법('한남동')을 걸러가며 잡는다.
        //
        // 남긴 것은 단어만으로 확실한 것들이다. API 키가 없어도 도는 안전망이다.
        KEYWORDS.put(RiskCategory.INSULT,
                List.of("병신", "씨발", "시발", "좆", "지랄", "미친놈"));
        KEYWORDS.put(RiskCategory.PREJUDICE,
                List.of("여자들은 원래", "남자들은 원래", "장애인 같", "여자가 무슨", "남자가 무슨"));
    }

    private static final List<PrivacyRule> PRIVACY_RULES = List.of(
            new PrivacyRule(Pattern.compile("(?<!\\d)01[016789]-?\\d{3,4}-?\\d{4}(?!\\d)"), "전화번호"),
            new PrivacyRule(Pattern.compile("(?<!\\d)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])-?[1-4]\\d{6}(?!\\d)"), "주민등록번호"),
            new PrivacyRule(Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"), "이메일 주소"),
            new PrivacyRule(Pattern.compile("(?<![\\d-])\\d{2,6}-\\d{2,6}-\\d{2,7}(?:-\\d{1,3})?(?![\\d-])(?<!\\d{4}-\\d{2}-\\d{2})"), "계좌번호로 보이는 숫자")
    );

    public List<Hit> detect(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }

        String scrubbed = text;
        for (String exception : PROFANITY_EXCEPTIONS) {
            scrubbed = scrubbed.replace(exception, " ");
        }
        final String target = scrubbed;
        KEYWORDS.forEach((category, words) -> words.stream()
                .filter(target::contains)
                .findFirst()
                .ifPresent(word -> hits.add(new Hit(
                        category,
                        scoreOf(category),
                        category == RiskCategory.INSULT
                                ? "'" + word + "' 같은 욕설이 들어 있습니다."
                                : "'" + word + "' 처럼 속성을 판단의 근거로 삼는 표현이 들어 있습니다."
                ))));

        for (PrivacyRule rule : PRIVACY_RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                hits.add(new Hit(
                        RiskCategory.PRIVACY,
                        0.85,
                        rule.label() + "로 보이는 값(" + matcher.group() + ")이 나옵니다. "
                                + "공개해도 되는 정보인지가 관건입니다."
                ));
                break; // 한 문장에서 개인정보는 한 건만 보고한다
            }
        }

        return hits;
    }

    private double scoreOf(RiskCategory category) {
        return switch (category) {
            // 욕설은 감탄형인지 대상이 있는지 단어만으로는 모른다. 낮게 두고 맥락 검토가 올려 준다.
            case INSULT -> 0.3;
            case PREJUDICE -> 0.6;
            case PRIVACY -> 0.75;
            default -> 0.35;
        };
    }

    public record Hit(RiskCategory category, double score, String reason) {}

    private record PrivacyRule(Pattern pattern, String label) {}
}
