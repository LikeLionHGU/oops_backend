package com.example.oops.domain;

/**
 * 영상 유형.
 *
 * 타깃은 편집을 위임하는 토크·인터뷰·팟캐스트 채널이다.
 * 즉흥 발언이 많고, 다른 사람·회사·사건을 자주 언급하며,
 * 편집자가 붙인 자막이 원래 발언과 달라질 수 있는 구조다.
 *
 * 경제 지표나 투자 판단을 다루는 영상은 대상에서 뺐다.
 * 그쪽은 대본 기반이라 대본만 검토해도 대부분 해결되고,
 * 우리가 영상 단위로 볼 이유가 약하다.
 */
public enum ContentGenre {

    TALK_PODCAST("토크·인터뷰·팟캐스트", "즉흥 발언과 편집 자막에서 확인할 지점이 생긴다"),
    GENERAL("일반", "특정 유형으로 분류되지 않음");

    private final String label;
    private final String note;

    ContentGenre(String label, String note) {
        this.label = label;
        this.note = note;
    }

    public String getLabel() {
        return label;
    }

    public String getNote() {
        return note;
    }

    /** 대화형 콘텐츠인지. 확인할 지점이 더 많으므로 넓게 훑는다. */
    public boolean isConversational() {
        return this == TALK_PODCAST;
    }

    public static ContentGenre fromOrDefault(String value, ContentGenre fallback) {
        if (value == null || value.isBlank()) return fallback;
        for (ContentGenre genre : values()) {
            if (genre.name().equalsIgnoreCase(value.trim())) {
                return genre;
            }
        }
        return fallback;
    }
}
