package com.example.oops.analyzer;

/** 분석기 한 번 실행의 내부 기록(JSON)과, 쓴 기준 버전(없으면 null). */
public record AnalyzerTrace(String json, String criteriaVersion) {}
