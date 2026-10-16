package com.example.oops.service;

import com.example.oops.config.OopsProperties;
import com.example.oops.config.SearchLogProperties;
import com.example.oops.news.SearchTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 영상별 검색 기록 파일. (2026-10 고도화)
 *
 *   저장   분석이 끝날 때(성공·실패 모두) {storage}/search-logs/{videoId}.json. 재분석하면 덮어쓴다
 *   조회   GET /api/v1/videos/{id}/search-log
 *   삭제   영상을 지울 때 같이, 그리고 retention-days(기본 14일)가 지난 파일은 6시간마다 정리
 *
 * 기록 저장이 실패해도 분석 결과에는 영향을 주지 않는다. 로그만 남기고 넘어간다.
 */
@Slf4j
@Service
public class SearchLogService {

    private static final String DIR = "search-logs";

    private final OopsProperties oopsProperties;
    private final SearchLogProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public SearchLogService(OopsProperties oopsProperties, SearchLogProperties properties) {
        this.oopsProperties = oopsProperties;
        this.properties = properties;
    }

    Path dir() {
        String location = oopsProperties.storage() == null || oopsProperties.storage().location() == null
                ? "./uploads" : oopsProperties.storage().location();
        return Paths.get(location).toAbsolutePath().normalize().resolve(DIR);
    }

    Path file(long videoId) {
        return dir().resolve(videoId + ".json");
    }

    /** 지금 스레드에 모인 검색 기록을 저장한다. 검색이 없었으면 빈 기록을 남긴다. */
    public void saveCurrent(long videoId) {
        if (!properties.enabledOrDefault()) return;
        try {
            Files.createDirectories(dir());
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(SearchTrace.snapshot());
            Files.writeString(file(videoId), json, StandardCharsets.UTF_8);
            log.info("[search-log] videoId={} 저장 → {}", videoId, file(videoId));
        } catch (Exception e) {
            log.warn("[search-log] videoId={} 저장 실패: {}", videoId, e.getMessage());
        }
    }

    public Optional<String> read(long videoId) {
        Path f = file(videoId);
        if (!Files.exists(f)) return Optional.empty();
        try {
            return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[search-log] videoId={} 읽기 실패: {}", videoId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(long videoId) {
        try {
            Files.deleteIfExists(file(videoId));
        } catch (IOException e) {
            log.warn("[search-log] videoId={} 삭제 실패: {}", videoId, e.getMessage());
        }
    }

    /** 보관 기간이 지난 기록을 지운다. 6시간마다 (서버 켜고 10분 뒤 첫 실행). */
    @Scheduled(initialDelay = 10 * 60 * 1000L, fixedDelay = 6 * 60 * 60 * 1000L)
    public void purgeExpired() {
        int days = properties.retentionDaysOrDefault();
        if (days <= 0 || !Files.isDirectory(dir())) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int removed = 0;
        try (Stream<Path> files = Files.list(dir())) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(f);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("[search-log] 정리 실패: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("[search-log] {}일 지난 검색 기록 {}개를 지웠습니다", days, removed);
        }
    }
}
