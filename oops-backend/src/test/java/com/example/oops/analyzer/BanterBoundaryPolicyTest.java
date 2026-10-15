package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Free prompt assembly checks, not model accuracy or human case adjudication. */
class BanterBoundaryPolicyTest {
    @Test void changesOnlyTheKnownBoundaryAndKeepsAllContractsAndReferences() {
        for (String prompt : List.of(SourceRelationPrompts.DISCOVERY,
                SourceRelationPrompts.verification(0), SourceRelationPrompts.verification(8),
                CandidateReviewPrompts.DISCOVERY_PROMPT, CandidateReviewPrompts.VERIFICATION_PROMPT)) {
            String reference = "\ncontextReference={\"guidelines\":[\"frozen\"]}";
            String original = prompt + reference;
            String input = "{\"raw\":[\"unchanged\"],\"candidates\":[\"unchanged\"]}";
            var pair = BanterBoundaryPolicy.pair(original, input);
            assertThat(pair.baselineSystem()).isEqualTo(original);
            assertThat(pair.input()).isEqualTo(input);
            int offset = CandidateReviewPrompts.POLICY.indexOf(BanterBoundaryPolicy.ORIGINAL_BOUNDARY);
            assertThat(offset).isNotNegative();
            assertThat(pair.comparisonSystem()).isEqualTo(original.substring(0, offset)
                    + BanterBoundaryPolicy.BOUNDARY
                    + original.substring(offset + BanterBoundaryPolicy.ORIGINAL_BOUNDARY.length()));
            assertThat(pair.comparisonSystem()).endsWith(reference);
        }
    }

    @Test void rejectsUnknownPrefixesRepeatedApplicationAndCompletedComparison40() {
        assertThatThrownBy(() -> BanterBoundaryPolicy.replacePolicy(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BanterBoundaryPolicy.replacePolicy("unknown")).isInstanceOf(IllegalArgumentException.class);
        String once = BanterBoundaryPolicy.replacePolicy(SourceRelationPrompts.verification(0));
        assertThatThrownBy(() -> BanterBoundaryPolicy.replacePolicy(once)).isInstanceOf(IllegalArgumentException.class);
        String previous = ContextJudgmentPolicy.replacePolicy(SourceRelationPrompts.verification(0));
        assertThatThrownBy(() -> BanterBoundaryPolicy.replacePolicy(previous)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BanterBoundaryPolicy.pair(SourceRelationPrompts.DISCOVERY, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void preservesPositiveMechanismsAndRequiresNeitherRepetitionNorImaginedConsent() {
        String prompt = BanterBoundaryPolicy.replacePolicy(SourceRelationPrompts.verification(0));
        assertThat(prompt).contains("한 번이어도 검토할 수 있다", "합의를 추정하지 않는다",
                "부정적 평가어가 있다는 이유만으로", "실제 동조·추가 공격은 구별한다",
                "주민 전체 일반화는 조롱의 필수 조건이 아니다", "구체적 신체 훼손 비유",
                "UNCERTAIN으로 부족한 정보를 명시한다");
        assertThat(BanterBoundaryPolicy.BOUNDARY).doesNotContain("피식대학", "뻔뻔", "거짓말 게임", "videoId");
    }
}
