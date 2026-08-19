package com.example.oops.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유튜브 주소에서 영상 id 를 뽑아 삽입용 주소를 만든다.
 *
 * **왜 필요한가.**
 *
 * 유튜브로 등록한 영상은 서버에 파일이 없다.
 * 분석할 때 파이썬 쪽에서 임시 폴더에 받아 쓰고 끝나면 지운다.
 * 그래서 streamUrl 이 null 이고, 프론트는 sourceUrl 로 재생을 시도하게 된다.
 *
 * 그런데 `<video src="https://www.youtube.com/watch?v=...">` 는 **검은 화면**이 된다.
 * 그 주소는 영상 파일이 아니라 HTML 페이지라서 video 태그가 재생할 수 없다.
 * 오류도 안 나고 그냥 아무것도 안 나오기 때문에
 * "서버가 유튜브에 접속을 못 하나" 로 오해하기 쉽다. 서버는 아무 상관이 없다.
 *
 * 유튜브 영상은 iframe 삽입 플레이어로만 재생할 수 있다.
 * 그래서 프론트가 바로 쓸 수 있는 주소를 서버가 만들어 준다.
 * 주소 형태가 여러 가지라 프론트마다 파싱을 다시 만드는 것보다 여기서 한 번 하는 편이 낫다.
 */
public final class YouTubeUrls {

    /**
     * 유튜브 영상 id 를 담고 있는 주소 형태들.
     *
     *   https://www.youtube.com/watch?v=ID
     *   https://youtu.be/ID
     *   https://www.youtube.com/shorts/ID
     *   https://www.youtube.com/embed/ID
     *   https://www.youtube.com/live/ID
     *
     * id 는 11글자 고정이다.
     */
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})");

    private YouTubeUrls() {}

    /** 유튜브 주소로 보이는지. 형태만 본다. */
    public static boolean isYouTube(String url) {
        return videoId(url) != null;
    }

    /** 영상 id. 못 뽑으면 null */
    public static String videoId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * iframe 에 넣을 주소. 유튜브가 아니면 null.
     *
     * enablejsapi=1 을 붙이는 이유는 검토 카드를 눌렀을 때
     * 그 시각으로 이동해야 하기 때문이다.
     * 이게 없으면 프론트가 플레이어를 조작할 수 없어서
     * 사용자가 타임코드를 보고 직접 찾아가야 한다. 이 도구의 핵심이 시각 이동인데
     * 유튜브 영상만 그게 안 되면 반쪽이 된다.
     *
     * origin 은 프론트가 붙인다. 서버는 프론트 도메인을 모른다.
     */
    public static String embedUrl(String url) {
        String id = videoId(url);
        return id == null ? null
                : "https://www.youtube.com/embed/%s?enablejsapi=1&rel=0".formatted(id);
    }
}
