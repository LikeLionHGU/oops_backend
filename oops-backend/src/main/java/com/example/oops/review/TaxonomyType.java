package com.example.oops.review;

import com.example.oops.domain.RiskCategory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 맥락 검토 8유형. (2026-10 고도화)
 *
 * 분류 기준은 "도덕적으로 얼마나 나쁜가" 가 아니라 "제작자가 왜 놓치는가" 다.
 * 정의·확인 항목·예시는 ReviewPrompts 에 있고, 여기는 유형의 이름과 우선순위만 든다.
 *
 * **precedence 는 한 발언이 여러 유형에 걸릴 때 주 유형을 고르는 순서다.**
 * 숫자가 작을수록 먼저다. 더 구체적이고 심각한 것이 앞에 온다.
 * 카드는 한 장만 만들고, 나머지 유형은 이유에 "함께 해당" 으로 적는다.
 * (미성년자에 대한 성적 의미 부여는 유형과 상관없이 항상 최우선이다 — ContextReviewAnalyzer)
 */
public enum TaxonomyType {

    VICTIM_BLAMING(RiskCategory.VICTIM_BLAMING, "피해자 조롱·피해 축소", 1, true),
    SEXUAL_HARASSMENT(RiskCategory.SEXUAL_HARASSMENT, "성적 대상화·성희롱", 2, true),
    PREJUDICE(RiskCategory.PREJUDICE, "집단·속성 편견·차별·혐오", 3, true),
    PRIVACY(RiskCategory.PRIVACY, "사생활·신상 노출", 4, true),
    DANGEROUS_ACT(RiskCategory.DANGEROUS_ACT, "위험·불법 행동 미화", 5, false),
    SOCIOPOLITICAL_CONTEXT(RiskCategory.SOCIOPOLITICAL_CONTEXT, "역사·정치·사회적 맥락", 6, true),
    INSULT(RiskCategory.INSULT, "욕설·모욕·폄하·조롱", 7, false),
    COMMUNITY_SLANG(RiskCategory.COMMUNITY_SLANG, "커뮤니티 말투·밈", 8, false);

    private final RiskCategory category;
    private final String label;
    private final int precedence;
    /**
     * 대상(target)을 반드시 적어야 하는 유형인지.
     *
     * 욕설 중 감탄형은 대상이 없고, 커뮤니티 말투와 위험 행동은 대상이 '표현'이나 '행동'이다.
     * 그 셋은 대상 칸이 비어도 버리지 않는다. 나머지는 대상을 못 적으면 버린다.
     */
    private final boolean targetRequired;

    TaxonomyType(RiskCategory category, String label, int precedence, boolean targetRequired) {
        this.category = category;
        this.label = label;
        this.precedence = precedence;
        this.targetRequired = targetRequired;
    }

    public RiskCategory category() {
        return category;
    }

    public String label() {
        return label;
    }

    public int precedence() {
        return precedence;
    }

    public boolean targetRequired() {
        return targetRequired;
    }

    /** 모델이 보낸 문자열을 유형으로. 모르는 값이면 빈 값 (기본값으로 바꾸지 않는다). */
    public static Optional<TaxonomyType> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String v = value.trim();
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(v))
                .findFirst();
    }

    /** 우선순위 순으로 정렬한 사본. */
    public static List<TaxonomyType> byPrecedence(List<TaxonomyType> types) {
        return types.stream()
                .distinct()
                .sorted(Comparator.comparingInt(TaxonomyType::precedence))
                .toList();
    }

    /** RiskCategory → 유형. 8유형이 아닌 예전 카테고리면 빈 값. */
    public static Optional<TaxonomyType> of(RiskCategory category) {
        return Arrays.stream(values()).filter(t -> t.category == category).findFirst();
    }
}
