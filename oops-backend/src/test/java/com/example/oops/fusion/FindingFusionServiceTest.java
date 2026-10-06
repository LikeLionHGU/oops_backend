package com.example.oops.fusion;

import com.example.oops.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검토 후보 병합.
 *
 * 사용자가 가장 먼저 불평한 부분이다.
 * "이거는 왜 같은 논란을 계속 보여주는거야?"
 *
 * 분석기 여러 개가 같은 장면을 각자 보고하고,
 * 영상 내내 떠 있는 자막은 프레임마다 다시 잡힌다.
 * 그대로 두면 같은 카드가 7장, 11장씩 쌓인다.
 */
class FindingFusionServiceTest {

    private FindingFusionService service;

    @BeforeEach
    void setUp() {
        service = new FindingFusionService();
    }

    private RiskFinding speech(RiskCategory category, double score,
                               long startMs, String text, String target) {
        return RiskFinding.builder()
                .eventType(TimelineEventType.SPEECH)
                .category(category)
                .source(EvidenceSource.SUBTITLE)
                .score(score)
                .startMs(startMs)
                .endMs(startMs + 2000)
                .text(text)
                .reason("확인이 필요한 대목입니다. 무엇을 봐야 하는지 적혀 있습니다.")
                .target(target)
                .build();
    }

    private RiskFinding caption(RiskCategory category, double score,
                                long startMs, String text, String target) {
        return RiskFinding.builder()
                .eventType(TimelineEventType.CAPTION)
                .category(category)
                .source(EvidenceSource.VISION)
                .score(score)
                .startMs(startMs)
                .endMs(startMs + 2000)
                .captionText(text)
                .reason("화면에 나타난 구체적인 표현입니다.")
                .target(target)
                .build();
    }

    @Test
    @DisplayName("같은 대상이어도 서로 다른 시점이면 별도 후보로 남긴다")
    void keepsSharedTargetAtDistantTimesSeparate() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.BELITTLEMENT, 0.6, 5000, "패스트푸드 같은 맛이야", "패스트푸드"),
                speech(RiskCategory.BELITTLEMENT, 0.5, 300000, "패스트푸드 같은 맛이야", "패스트푸드")
        ));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("같은 시점이어도 서로 다른 위험 유형은 대상명만으로 합치지 않는다")
    void keepsDifferentCategoriesSeparate() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.BELITTLEMENT, 0.6, 5000, "패스트푸드 같은 맛이야", "패스트푸드"),
                speech(RiskCategory.GENERALIZATION, 0.5, 5000, "패스트푸드 같은 맛이야", "패스트푸드")
        ));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("STT와 같은 문구가 OCR에도 있어도 점수 자체는 올리지 않는다")
    void doesNotBoostScoreForRepeatedCrossModalEvidence() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.MOCKERY, 0.6, 5000, "그 음식은 정말 별로야", "음식"),
                caption(RiskCategory.MOCKERY, 0.7, 5000, "그 음식은 정말 별로야", "음식")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isCrossModal()).isTrue();
        assertThat(result.get(0).getScore()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("같은 문장이 여러 번 잡히면 한 건으로 묶고 등장 시각을 남긴다")
    void mergesRepeatedText() {
        // 고정 자막을 OCR 이 프레임마다 다시 읽는 경우.
        // 구간만 보여주면 "00:26 ~ 00:59 사이 어딘가" 로 뭉뚱그려져 찾을 수 없다.
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.MOCKERY, 0.6, 26000, "너무 특색이 없어가지고", "메뉴"),
                speech(RiskCategory.MOCKERY, 0.6, 29000, "너무 특색이 없어가지고", "메뉴"),
                speech(RiskCategory.MOCKERY, 0.6, 32000, "너무 특색이 없어가지고", "메뉴")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOccurrenceTimes()).contains("00:26", "00:29", "00:32");
        assertThat(result.get(0).getStartMs()).isEqualTo(26000);
        assertThat(result.get(0).getEndMs()).isEqualTo(34000);
    }

    @Test
    @DisplayName("관련 없는 지적은 따로 남긴다")
    void keepsUnrelatedSeparate() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.MOCKERY, 0.6, 5000, "이 가게 맛이 별로야", "가게"),
                speech(RiskCategory.PRIVACY, 0.8, 300000, "전화번호는 010으로 시작해요", "전화번호")
        ));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("확신도가 높은 쪽이 대표가 된다")
    void picksHighestScore() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.MOCKERY, 0.4, 5000, "같은 문장이다 이것은", "대상"),
                speech(RiskCategory.MOCKERY, 0.9, 5000, "같은 문장이다 이것은", "대상")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("버려지는 후보의 참고 자료를 대표가 넘겨받는다")
    void carriesReferences() {
        // 은어 사전이 확신도 높게 잡고, 맥락 분석기가 기사와 함께 잡는 경우.
        // 대표는 사전 쪽인데 근거는 맥락 쪽이 들고 있다. 그냥 두면 링크가 사라진다.
        RiskFinding withoutRefs = speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.9, 5000, "같은 대목", "OO사건");
        RiskFinding withRefs = speech(RiskCategory.TIMING_SENSITIVE, 0.5, 5000, "같은 대목", "OO사건");
        withRefs.addReference(ReviewReference.of(
                "OO사건 재판 진행", "한국일보", "https://example.com/a", "2026-08-01", "발췌"));

        List<RiskFinding> result = service.fuse(List.of(withoutRefs, withRefs));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(0.9);          // 대표는 사전 쪽
        assertThat(result.get(0).getReferences()).hasSize(1);         // 근거는 살아남는다
    }

    @Test
    @DisplayName("우선순위 내림차순으로 정렬해서 준다")
    void sortsByPriority() {
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.SCREEN_TEXT, 0.3, 1000, "덜 중요한 내용입니다", "가"),
                speech(RiskCategory.PRIVACY, 0.9, 2000, "전화번호가 그대로 나옵니다", "나")
        ));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCategory()).isEqualTo(RiskCategory.PRIVACY);
    }

    @Test
    @DisplayName("빈 목록도 처리한다")
    void handlesEmpty() {
        assertThat(service.fuse(List.of())).isEmpty();
    }
}
