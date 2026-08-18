package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "oops")
public record OopsProperties(Storage storage, Analysis analysis) {

    /**
     * 저장소 정리 정책.
     *
     * 삭제가 두 단계인 이유는, 지워야 하는 것과 남겨야 하는 것이 다르기 때문이다.
     *
     *   sourceRetentionHours  원본 영상 파일만 지운다. 리포트는 남는다.
     *   retentionDays         영상에 딸린 모든 것을 지운다. 리포트도 사라진다.
     *
     * 하나로 묶여 있으면 "원본은 오래 두기 싫은데 결과는 계속 보고 싶다" 를
     * 표현할 방법이 없다. 사용자가 원하는 건 대부분 그쪽이다.
     */
    public record Storage(String location, Integer retentionDays, Integer sourceRetentionHours) {

        /** 원본 미디어를 몇 시간 뒤에 지울지. 0 이하면 지우지 않는다. */
        public int sourceRetentionHoursOrDefault() {
            return sourceRetentionHours == null ? 24 : sourceRetentionHours;
        }

        /** 0 이하면 자동 정리를 하지 않는다. */
        public int retentionDaysOrDefault() {
            return retentionDays == null ? 0 : retentionDays;
        }
    }

    /** enabled-analyzers 에 적힌 키를 가진 분석기만 파이프라인에서 실행된다. */
    public record Analysis(List<String> enabledAnalyzers) {}
}
