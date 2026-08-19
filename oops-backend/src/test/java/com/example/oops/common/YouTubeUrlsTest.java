package com.example.oops.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유튜브 주소에서 영상 id 뽑기.
 *
 * 이게 틀리면 embedUrl 이 null 이 되고, 프론트는 재생할 방법이 없어
 * **검은 화면**을 띄운다. 오류도 안 나서 원인을 찾기 어렵다.
 * 실제로 "서버가 유튜브에 접속을 못 하나" 로 한참 오해했던 문제라
 * 주소 형태별로 다 걸어둔다.
 */
class YouTubeUrlsTest {

    private static final String ID = "dQw4w9WgXcQ";

    @Test
    @DisplayName("일반 시청 주소")
    void watchUrl() {
        assertThat(YouTubeUrls.videoId("https://www.youtube.com/watch?v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeUrls.videoId("http://youtube.com/watch?v=" + ID)).isEqualTo(ID);
    }

    @Test
    @DisplayName("공유 버튼이 주는 짧은 주소")
    void shortUrl() {
        assertThat(YouTubeUrls.videoId("https://youtu.be/" + ID)).isEqualTo(ID);
    }

    @Test
    @DisplayName("쇼츠·삽입·라이브 주소")
    void otherForms() {
        assertThat(YouTubeUrls.videoId("https://www.youtube.com/shorts/" + ID)).isEqualTo(ID);
        assertThat(YouTubeUrls.videoId("https://www.youtube.com/embed/" + ID)).isEqualTo(ID);
        assertThat(YouTubeUrls.videoId("https://www.youtube.com/live/" + ID)).isEqualTo(ID);
    }

    @Test
    @DisplayName("뒤에 파라미터가 붙어도 뽑는다")
    void withExtraParams() {
        // 공유 주소에는 재생목록·시작시각·추적값이 자주 붙는다
        assertThat(YouTubeUrls.videoId(
                "https://www.youtube.com/watch?v=" + ID + "&t=42s&list=PLxxx")).isEqualTo(ID);
        assertThat(YouTubeUrls.videoId(
                "https://youtu.be/" + ID + "?si=abcdefg")).isEqualTo(ID);
    }

    @Test
    @DisplayName("v 가 첫 파라미터가 아니어도 뽑는다")
    void vNotFirst() {
        assertThat(YouTubeUrls.videoId(
                "https://www.youtube.com/watch?list=PLxxx&v=" + ID)).isEqualTo(ID);
    }

    @Test
    @DisplayName("유튜브가 아니면 null — 아무 주소나 iframe 에 넣으면 안 된다")
    void notYouTube() {
        assertThat(YouTubeUrls.videoId("https://vimeo.com/123456")).isNull();
        assertThat(YouTubeUrls.videoId("https://example.com/video.mp4")).isNull();
        assertThat(YouTubeUrls.videoId("https://www.youtube.com/@somechannel")).isNull();
        assertThat(YouTubeUrls.videoId(null)).isNull();
        assertThat(YouTubeUrls.videoId("")).isNull();
    }

    @Test
    @DisplayName("삽입 주소에는 enablejsapi 가 있어야 한다")
    void embedUrlAllowsSeeking() {
        // 이게 없으면 프론트가 플레이어를 조작할 수 없다.
        // 카드를 눌러도 그 시각으로 못 가면 이 도구는 반쪽이 된다.
        String embed = YouTubeUrls.embedUrl("https://youtu.be/" + ID);

        assertThat(embed).contains(ID).contains("enablejsapi=1");
        assertThat(embed).startsWith("https://www.youtube.com/embed/");
    }

    @Test
    @DisplayName("업로드한 영상은 삽입 주소가 없다")
    void noEmbedForUploads() {
        // 업로드 영상은 sourceUrl 자체가 null 이다
        assertThat(YouTubeUrls.embedUrl(null)).isNull();
        assertThat(YouTubeUrls.isYouTube(null)).isFalse();
    }
}
