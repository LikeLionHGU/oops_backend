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
 * 특정 온라인 커뮤니티에서 쓰이는 표현을 찾아낸다.
 *
 * 왜 별도로 두었나:
 * 이건 욕설과 성격이 다르다. 단어 자체는 평범해 보이는데
 * 특정 커뮤니티 안에서만 다른 뜻으로 쓰이는 경우가 많다.
 * 출연자나 편집자가 그 맥락을 모르고 썼다가 나중에 문제가 되는 일이 실제로 있었다.
 * 기획서에서 말한 "편집자가 모르는 사회적 맥락" 사각지대가 정확히 이것이다.
 *
 * LLM 만으로는 부족한 이유:
 * 사투리나 일상 표현과 겹치는 것이 많아 모델이 애매하면 그냥 넘어간다.
 * 사전으로 한 번 걸러 "확인해 볼 만하다" 는 신호를 주고,
 * 실제 맥락 판단은 LLM 과 제작자에게 맡긴다.
 *
 * 점수를 낮게 준 이유:
 * 이 목록에 걸린다고 문제가 있는 것이 아니다.
 * 경상도 사투리를 그대로 쓴 것일 수도 있고, 다른 뜻으로 썼을 수도 있다.
 * 우리는 판정하지 않는다. 확인할 지점만 알린다.
 *
 * 이 목록은 팀에서 계속 채워야 한다. 개발 지식 없이도 추가할 수 있다.
 */
@Component
public class CommunitySlangRules {

    /**
     * 표현 → 왜 확인이 필요한지.
     *
     * 여기 담긴 것은 "쓰면 안 되는 말" 목록이 아니라
     * "이 표현이 나왔으니 어떤 맥락으로 쓴 건지 한 번 보라" 는 목록이다.
     */
    private static final Map<String, String> SLANG = new LinkedHashMap<>();

    static {
        // 특정 인물·사건을 조롱하는 뜻으로 쓰인 사례가 보도된 표현
        SLANG.put("운지", "특정 인물의 죽음을 조롱하는 뜻으로 쓰인 사례가 보도된 표현입니다.");
        SLANG.put("노무", "특정 인물의 성을 비하 접두사처럼 쓰는 용례가 알려진 표현입니다.");
        SLANG.put("슨상님", "특정 정치인을 조롱하는 뜻으로 쓰인 사례가 있는 표현입니다.");
        SLANG.put("홍어", "특정 지역 출신을 비하하는 은어로 쓰인 사례가 보도된 표현입니다.");
        SLANG.put("전라디언", "특정 지역 출신을 비하하는 은어입니다.");
        SLANG.put("깨시민", "특정 정치 성향을 조롱하는 은어로 쓰입니다.");
        SLANG.put("좌빨", "특정 정치 성향을 비하하는 표현입니다.");
        SLANG.put("좌좀", "특정 정치 성향을 비하하는 표현입니다.");
        SLANG.put("수꼴", "특정 정치 성향을 비하하는 표현입니다.");

        // 커뮤니티 안에서 반대 의미로 쓰이는 말
        SLANG.put("민주화", "일부 커뮤니티에서 본래 뜻과 반대로 '깎아내리다' 는 의미로 쓰인 사례가 있습니다.");
        SLANG.put("산업화", "일부 커뮤니티에서 '치켜세우다' 는 은어로 쓰인 사례가 있습니다.");

        // 세대·성별 갈등 관련
        SLANG.put("한남", "남성을 비하하는 은어로 쓰입니다.");
        SLANG.put("김치녀", "여성을 비하하는 은어로 쓰입니다.");
        SLANG.put("된장녀", "여성을 비하하는 은어로 쓰입니다.");
        SLANG.put("틀딱", "고령층을 비하하는 은어입니다.");
        SLANG.put("급식충", "청소년을 비하하는 은어입니다.");
        SLANG.put("맘충", "양육자를 비하하는 은어입니다.");
        SLANG.put("설거지론", "특정 집단을 조롱하는 커뮤니티 담론에서 나온 표현입니다.");

        // 커뮤니티 인용 신호
        SLANG.put("일베", "해당 커뮤니티를 언급하는 것만으로도 시청자 반응이 갈릴 수 있습니다.");
        SLANG.put("이기야", "특정 커뮤니티에서 쓰이는 말투로 알려져 있습니다.");
    }

    /**
     * 문장 끝의 '~노' 어미.
     *
     * 경상도 사투리에서도 흔히 쓰이므로 이것만으로는 아무것도 단정할 수 없다.
     * 다만 특정 커뮤니티 말투로도 알려져 있어서, 표준어 문장에 붙으면
     * 시청자가 다르게 읽을 수 있다. 그래서 아주 낮은 점수로만 알린다.
     *
     * 실제로 이 어미 때문에 논란이 된 사례가 여러 번 있었다.
     */
    private static final Pattern NO_ENDING =
            Pattern.compile("[가-힣]{2,}노[?!.]?\\s*$|[가-힣]{2,}노[?!]");

    /** 사투리라면 함께 나올 법한 말들. 이게 보이면 어미 신호를 낮춘다. */
    private static final List<String> DIALECT_HINTS = List.of(
            "머하노", "뭐하노", "가가", "억수로", "쫌", "마이", "우얄", "안캐", "그카", "이카");

    public List<Hit> detect(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }

        SLANG.forEach((word, note) -> {
            if (text.contains(word)) {
                hits.add(new Hit(RiskCategory.UNFAMILIAR_CONTEXT, 0.55,
                        "'" + word + "' — " + note, word));
            }
        });

        Matcher matcher = NO_ENDING.matcher(text.trim());
        if (matcher.find() && !looksLikeDialect(text)) {
            hits.add(new Hit(RiskCategory.UNFAMILIAR_CONTEXT, 0.35,
                    "문장 끝의 '~노' 어미입니다. 경상도 사투리로도 쓰이지만 "
                            + "특정 커뮤니티 말투로 읽히기도 해서 시청자 반응이 갈릴 수 있습니다.",
                    "~노 어미"));
        }
        return hits;
    }

    /** 사투리 어휘가 함께 보이면 그냥 사투리일 가능성이 높다. */
    private boolean looksLikeDialect(String text) {
        return DIALECT_HINTS.stream().anyMatch(text::contains);
    }

    /** category, score, reason, target */
    public record Hit(RiskCategory category, double score, String reason, String target) {}
}
