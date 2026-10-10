package com.example.oops.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 유튜브 영상을 **스크립트 텍스트**로 등록할 때 쓴다.
 *
 * 예전에는 링크만 받아서 서버가 영상을 내려받고 음성·화면 글자를 뽑았다.
 * 배포 서버(데이터센터 IP)에서는 유튜브가 다운로드를 막아서 지금은
 * 사용자가 유튜브 "스크립트 표시" 창에서 복사한 글을 붙여넣는 방식으로 바꿨다.
 * 분석 로직과 감지 방식은 그대로이고, 대본을 어디서 얻느냐만 다르다.
 *
 * - script : 붙여넣은 스크립트 (필수). "0:00 첫 줄 0:03 둘째 줄" 처럼 시각이 있으면 그대로 쓰고,
 *            시각이 없으면 글자 수로 추정한다.
 * - url    : 유튜브 링크 (선택). 내려받지 않는다. 리포트에서 영상을 임베드하는 데만 쓴다.
 *            프론트가 아직 링크 칸에 스크립트를 붙여넣어 보내는 경우도 받는다 —
 *            url 이 링크가 아니라 글이면 그 글을 스크립트로 본다.
 */
public record VideoRegisterRequest(
        String url,
        @JsonAlias({"transcript", "scriptText"})
        String script,
        String title,
        String channelName,
        /**
         * 영상 유형 (선택).
         * TALK_PODCAST / GENERAL
         * 비워두면 분석 중에 대본을 보고 자동으로 판별한다.
         */
        String genre
) {}
