package com.example.oops.domain;

/** 이 논란을 무엇을 근거로 잡아냈는지 */
public enum EvidenceSource {
    SUBTITLE,   // 자막 / STT 텍스트
    VISION,     // 영상 프레임 (포즈, 제스처)
    AUDIO,      // 음성 톤, 배경음
    COMMENT     // 유튜브 댓글
}
