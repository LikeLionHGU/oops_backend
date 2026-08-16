package com.example.oops.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알맹이 없는 사유 걸러내기.
 *
 * "특정한 맥락에서 사용될 수 있는 표현입니다" 는 형식만 갖춘 문장이다.
 * 제작자가 이걸 읽고 무엇을 확인해야 할지 알 수 없으므로 없느니만 못하다.
 */
class VagueReasonFilterTest {

    @Test
    @DisplayName("뭉뚱그린 짧은 문장은 버린다")
    void dropsVague() {
        assertThat(VagueReasonFilter.isUseful("특정한 맥락에서 사용될 수 있는 표현입니다"))
                .isFalse();
        assertThat(VagueReasonFilter.isUseful("논란이 될 수 있습니다"))
                .isFalse();
    }

    @Test
    @DisplayName("너무 짧으면 설명이 아니다")
    void dropsTooShort() {
        assertThat(VagueReasonFilter.isUseful("민감함")).isFalse();
        assertThat(VagueReasonFilter.isUseful("")).isFalse();
        assertThat(VagueReasonFilter.isUseful(null)).isFalse();
    }

    @Test
    @DisplayName("무엇을 확인할지 알 수 있으면 통과시킨다")
    void keepsConcrete() {
        assertThat(VagueReasonFilter.isUseful(
                "소개 중인 가게의 메뉴를 평가하는 대목입니다. 당사자가 볼 수 있습니다."))
                .isTrue();
    }

    @Test
    @DisplayName("뭉뚱그린 표현이 있어도 뒤에 구체적인 설명이 붙으면 통과시킨다")
    void keepsVagueButDetailed() {
        // 앞부분만 보고 버리면 진짜 설명까지 같이 날아간다.
        assertThat(VagueReasonFilter.isUseful(
                "논란이 될 수 있습니다. 해당 표현은 2019년 OO 사건 이후 "
                        + "특정 커뮤니티에서 다른 뜻으로 쓰인 사례가 보도됐습니다."))
                .isTrue();
    }
}
