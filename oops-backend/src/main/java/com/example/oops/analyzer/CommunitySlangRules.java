package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import com.example.oops.lexicon.ContextLexicon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 맥락을 안 봐도 되는 표현만 잡는 안전망.
 *
 * 사전(context-lexicon.json)에서 `needsContext: false` 인 항목만 본다.
 * '틀딱', '급식충', '전라디언' 처럼 일반적인 다른 뜻이 없는 표현들이다.
 * 앞뒤를 봐도 답이 같으므로 AI 를 부를 이유가 없다.
 *
 * 반대로 '7시', '수박', '포도' 처럼 일반 용법이 있는 표현은
 * 여기서 처리하지 않는다. ContextLexiconAnalyzer 가 앞뒤 맥락을 확인한 뒤에 올린다.
 * 여기서 같이 잡으면 "7시에 만나요" 가 그대로 카드가 된다.
 *
 * 이 분석기가 따로 남아 있는 이유는 **AI 키가 없어도 돌기 때문**이다.
 * 요청 한도에 걸려 LLM 분석기가 전부 죽어도 이건 살아 있다.
 */
@Component
@RequiredArgsConstructor
public class CommunitySlangRules {

    private final ContextLexicon lexicon;

    public List<Hit> detect(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }

        for (ContextLexicon.Match match : lexicon.match(text)) {
            // 맥락 확인이 필요한 건 여기서 올리지 않는다.
            // 확인 없이 올리면 일반 용법까지 전부 카드가 된다.
            if (match.entry().requiresContextCheck()) {
                continue;
            }
            hits.add(new Hit(
                    RiskCategory.UNFAMILIAR_CONTEXT,
                    match.score(),
                    match.entry().reason(),
                    match.matchedText()));
        }
        return hits;
    }

    public record Hit(RiskCategory category, double score, String reason, String target) {}
}
