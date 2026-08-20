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

    @Test
    @DisplayName("같은 대상을 지적하면 유형이 달라도 한 건으로 묶는다")
    void mergesBySharedTarget() {
        // 실제로 겪은 사례: "패스트푸드" 를 한쪽은 비하로, 다른 쪽은 일반화로 보고했다.
        // 사용자에게는 같은 지적이라 카드가 두 장 뜨면 안 된다.
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.BELITTLEMENT, 0.6, 5000, "패스트푸드 같은 맛이야", "패스트푸드"),
                speech(RiskCategory.GENERALIZATION, 0.5, 5000, "패스트푸드 같은 맛이야", "패스트푸드")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMergedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 문장이 여러 번 잡히면 한 건으로 묶고 등장 시각을 남긴다")
    void mergesRepeatedText() {
        // 고정 자막을 OCR 이 프레임마다 다시 읽는 경우.
        // 구간만 보여주면 "00:26 ~ 00:59 사이 어딘가" 로 뭉뚱그려져 찾을 수 없다.
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.MOCKERY, 0.6, 26000, "너무 특색이 없어가지고", "메뉴"),
                speech(RiskCategory.MOCKERY, 0.6, 35000, "너무 특색이 없어가지고", "메뉴"),
                speech(RiskCategory.MOCKERY, 0.6, 44000, "너무 특색이 없어가지고", "메뉴")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOccurrenceTimes()).contains("00:26", "00:35", "00:44");
        assertThat(result.get(0).getStartMs()).isEqualTo(26000);
        assertThat(result.get(0).getEndMs()).isEqualTo(46000);
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
    @DisplayName("한 줄에 지적이 둘이면 문장이 같아도 따로 남긴다")
    void doesNotMergeDifferentTargetsOnSameLine() {
        // 8초 영상에서 실제로 겪은 일이다.
        // 사전이 '나오노' 를 커뮤니티 어미로, 배경 확인이 같은 줄을
        // '정치판 논란' 으로 잡았는데 문장이 같다는 이유로 후보 5건이 1건이 됐다.
        // 사용자에게 커뮤니티 어미 카드는 아예 나가지 않았다.
        String line = "이런 게 왜 정치판에서 나오노";
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.6, 3000, line, "나오노"),
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.6, 3000, line, "나오노"),
                speech(RiskCategory.TIMING_SENSITIVE, 0.8, 3000, line, "정치판 논란")
        ));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RiskFinding::getTarget)
                .containsExactlyInAnyOrder("나오노", "정치판 논란");
    }

    @Test
    @DisplayName("같은 문장에 같은 대상이면 그대로 한 건이다")
    void stillMergesRepeatedSameTargetOnSameLine() {
        // 위 관문이 고정 자막 중복 제거를 깨뜨리지 않는지 확인한다.
        // 이쪽이 깨지면 같은 카드가 프레임 수만큼 쌓인다. 원래 불만의 시작이었다.
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.6, 3000, "독도는 일본땅", "독도"),
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.6, 9000, "독도는 일본땅", "독도"),
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.6, 15000, "독도는 일본땅", "독도")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMergedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("흡수된 지적은 문장이 같아도 대상 이름으로 카드에 남는다")
    void absorbedFindingSurvivesAsTargetName() {
        // 대표가 대상을 안 적었으면 병합은 그대로 일어난다.
        // 그때도 흡수된 쪽이 무엇을 지적했는지는 카드에 남아야 한다.
        String line = "이런 게 왜 정치판에서 나오노";
        List<RiskFinding> result = service.fuse(List.of(
                speech(RiskCategory.TIMING_SENSITIVE, 0.8, 3000, line, null),
                speech(RiskCategory.UNFAMILIAR_CONTEXT, 0.5, 3000, line, "나오노")
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReason()).contains("나오노");
    }

    @Test
    @DisplayName("빈 목록도 처리한다")
    void handlesEmpty() {
        assertThat(service.fuse(List.of())).isEmpty();
    }
}
