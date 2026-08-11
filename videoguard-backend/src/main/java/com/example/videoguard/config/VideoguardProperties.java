package com.example.videoguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "videoguard")
public record VideoguardProperties(Storage storage, Analysis analysis) {

    public record Storage(String location) {}

    /** enabled-analyzers 에 적힌 키를 가진 분석기만 파이프라인에서 실행된다. */
    public record Analysis(List<String> enabledAnalyzers) {}
}
