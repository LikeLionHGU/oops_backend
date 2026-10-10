package com.example.oops.expression;

import com.example.oops.analyzer.RiskRuleEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ExpressionDictionaryTest {
    private final ExpressionDictionary dictionary = new ExpressionDictionary();
    @ParameterizedTest
    @ValueSource(strings={"허버허버", "드릉드릉", "홍어", "민주화", "7시", "노무노무", "운지하다", "틀딱충", "씨발", "초딩", "ㅁㅈㅎ", "주먹"})
    void userWatchListPreservesMentionsWithoutClaimingIntent(String text) {
        assertThat(dictionary.detect(text)).hasSize(1).allSatisfy(h -> {
            assertThat(h.matchedText()).isEqualTo(text);
            assertThat(h.dictionaryVersion()).isEqualTo(dictionary.version());
            assertThat(h.commonUsageNote()).isNotBlank();
        });
    }
    @ParameterizedTest
    @ValueSource(strings={"좋노", "밥 묵었노?", "시발점", "새끼손가락", "민주화운동", "비가 오더라도", "군대", "선거", "칼로 사과를 썰어요", "내돈내산"})
    void excludesNoEndingAndOrdinarySubstringCollisions(String text) {
        assertThat(dictionary.detect(text)).isEmpty();
        assertThat(new RiskRuleEngine().detect(text)).isEmpty();
    }
    @Test void recordsRepeatedRawOffsetsAndParticlesWithoutChangingRawText() {
        String raw="😀 '허버허버'는 인용입니다. 허버허버! 홍어를 먹고 7시에 만나요.";
        var hits=dictionary.detect(raw);
        assertThat(hits).extracting(ExpressionDictionary.Hit::matchedText).containsExactly("허버허버", "허버허버", "홍어", "7시");
        hits.forEach(h -> assertThat(raw.substring(h.startOffset(), h.endOffset())).isEqualTo(h.matchedText()));
    }
    @Test void privacySafetyNetRemainsButProfanityDoesNotPublishContextCards() {
        assertThat(new RiskRuleEngine().detect("군대 선거 칼로 19금 씨발 허버허버 내돈내산")).isEmpty();
        assertThat(new RiskRuleEngine().detect("010-1234-5678")).hasSize(1);
    }
    @Test void everyRegisteredLiteralHasOneLongestMatchAndItsOwnId() throws Exception {
        try (var input=ExpressionDictionary.class.getResourceAsStream("/expression-dictionary.json")) {
            var archive=tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(input, ExpressionDictionary.Archive.class);
            for (var entry : archive.entries()) for (var alias : entry.aliases()) {
                assertThat(dictionary.detect("\"" + alias + "\""))
                        .as(alias).hasSize(1).allSatisfy(h -> {
                            assertThat(h.expressionId()).isEqualTo(entry.id());
                            assertThat(h.matchedText()).isEqualTo(alias);
                        });
            }
        }
    }
}
