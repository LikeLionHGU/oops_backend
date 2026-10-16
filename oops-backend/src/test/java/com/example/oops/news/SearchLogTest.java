package com.example.oops.news;

import com.example.oops.config.OopsProperties;
import com.example.oops.config.SearchLogProperties;
import com.example.oops.service.SearchLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 영상별 검색 기록: 모으기, 저장·조회, 14일 지나면 삭제, 끄기 */
class SearchLogTest {

    @TempDir
    Path tmp;

    private SearchLogService service(boolean enabled, int retentionDays) {
        OopsProperties props = new OopsProperties(new OopsProperties.Storage(tmp.toString(), 0, 24), null);
        return new SearchLogService(props, new SearchLogProperties(enabled, retentionDays));
    }

    private static NewsSearchClient.NewsItem item(String title, String url) {
        return new NewsSearchClient.NewsItem(title, "본문 " + title, "2026-10-01", url, null);
    }

    @Test
    @DisplayName("검색어·결과·AI 판정을 한 묶음으로 모아 영상별 파일로 저장하고 다시 읽는다")
    void collectsAndSaves() throws Exception {
        SearchTrace.begin(373);
        SearchTrace.analyzer("entity-check");
        SearchTrace.beginGroup("물망초 꽃말은 나를 잊지 마세요다", "물망초 꽃말이 나를 잊지 마세요잖아");
        SearchTrace.search("perplexity", "물망초 꽃말 나를 잊지 마세요", false,
                List.of(item("물망초 꽃말과 전설", "https://a.kr/1"), item("나를 잊지 마세요", "https://b.kr/2")));
        SearchTrace.evidence(List.of(new SearchTrace.Evidence(0, "물망초 꽃말과 전설", "https://a.kr/1", "언론 보도")));
        SearchTrace.decide("OK", 0.9, "자료와 부합합니다.", List.of(0), "OK (카드 없음)");

        SearchLogService service = service(true, 14);
        service.saveCurrent(373);

        String json = service.read(373).orElseThrow();
        assertThat(json)
                .contains("\"videoId\" : 373")
                .contains("\"searchCount\" : 1")
                .contains("사실 확인")
                .contains("물망초 꽃말 나를 잊지 마세요")
                .contains("https://b.kr/2")
                .contains("OK (카드 없음)");
        assertThat(tmp.resolve("search-logs/373.json")).exists();
    }

    @Test
    @DisplayName("보관 기간이 지난 기록만 지우고, 영상을 지우면 그 기록도 지운다")
    void purgesExpiredAndDeletes() throws Exception {
        SearchLogService service = service(true, 14);
        SearchTrace.begin(1);
        service.saveCurrent(1);
        SearchTrace.begin(2);
        service.saveCurrent(2);
        Path old = tmp.resolve("search-logs/1.json");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(15))));

        service.purgeExpired();
        assertThat(old).doesNotExist();
        assertThat(service.read(2)).isPresent();

        service.delete(2);
        assertThat(service.read(2)).isEmpty();
    }

    @Test
    @DisplayName("꺼 두면 파일을 만들지 않는다")
    void disabled() {
        SearchTrace.begin(5);
        service(false, 14).saveCurrent(5);
        assertThat(tmp.resolve("search-logs/5.json")).doesNotExist();
    }
}
