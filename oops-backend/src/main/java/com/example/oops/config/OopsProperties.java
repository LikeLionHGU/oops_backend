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
    public record Analysis(List<String> enabledAnalyzers, Boolean factCheckScreenText) {

        /**
         * 사실 확인이 **화면 글자까지** 볼지. 기본은 발언만 본다.
         *
         * 사실 확인을 껐던 이유가 전부 화면 글자에서 나왔다.
         * 메뉴판의 "김치찌개 8000원" 을 "평균 가격" 기사와 대조해 틀렸다고
         * 올리는 식이다. 가게마다 값이 다른 게 당연한데 그걸 오류로 봤다.
         *
         * 발언 쪽은 성격이 다르다. "그 회사 2019년에 만들어졌죠" 는
         * 근거 기사를 붙여 대조할 수 있고, 이 도구가 가장 잘하는 일이다.
         * 그래서 둘을 갈라서 발언만 먼저 켠다.
         *
         * 화면 쪽은 편집자가 지난 자막을 복사해 숫자만 안 고친 경우를 잡아
         * 값이 크지만(README 참고), 실제 영상으로 검증한 적이 없다.
         * 검증하고 나서 이 값을 true 로 바꾸면 된다. 코드는 그대로 있다.
         */
        public boolean factCheckScreenTextOrDefault() {
            return factCheckScreenText != null && factCheckScreenText;
        }
    }
}
