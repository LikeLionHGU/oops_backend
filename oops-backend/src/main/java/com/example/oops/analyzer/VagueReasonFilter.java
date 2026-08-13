package com.example.oops.analyzer;

import java.util.List;

/**
 * 알맹이가 없는 사유를 걸러낸다.
 *
 * LLM 이 확신이 없을 때 "특정한 맥락에서 사용될 수 있는 표현입니다" 같은
 * 문장을 만들어 낸다. 형식은 갖췄지만 제작자가 무엇을 확인해야 할지 알 수 없다.
 * 이런 항목은 없느니만 못하므로 버린다.
 *
 * 프롬프트로도 막고 있지만 모델이 가끔 어긴다. 마지막 방어선이다.
 */
final class VagueReasonFilter {

    /** 이 표현들만으로 이루어진 사유는 알맹이가 없다고 본다. */
    private static final List<String> VAGUE_MARKERS = List.of(
            "특정한 상황", "특정한 맥락", "특정 상황에서", "맥락에서 사용될 수 있",
            "관련이 있을 수 있", "문제가 될 수 있는 표현", "민감한 주제를 다루",
            "확인이 필요한 대목", "논란이 될 수 있습니다"
    );

    private VagueReasonFilter() {
    }

    /** 쓸 만한 사유인지 */
    static boolean isUseful(String reason) {
        if (reason == null) {
            return false;
        }
        String trimmed = reason.trim();

        // 너무 짧으면 설명이 아니다
        if (trimmed.length() < 15) {
            return false;
        }

        // 뭉뚱그린 표현이 있는데 문장이 짧으면, 그게 내용의 전부라는 뜻이다.
        // 길면 뒤에 구체적인 설명이 붙어 있을 가능성이 높으므로 통과시킨다.
        boolean vague = VAGUE_MARKERS.stream().anyMatch(trimmed::contains);
        return !vague || trimmed.length() >= 45;
    }
}
