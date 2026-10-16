package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 영상별 검색 기록 저장 설정. (2026-10 고도화)
 *
 *   enabled        검색어·검색 결과·AI 판정을 영상마다 JSON 파일로 남길지 (기본 켬)
 *   retention-days 이 날수가 지난 기록은 자동으로 지운다 (기본 14일, 0 이하면 지우지 않음)
 *
 * 파일은 oops.storage.location 아래 search-logs/{videoId}.json 에 둔다.
 */
@ConfigurationProperties(prefix = "oops.search-log")
public record SearchLogProperties(Boolean enabled, Integer retentionDays) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public int retentionDaysOrDefault() {
        return retentionDays == null ? 14 : retentionDays;
    }
}
