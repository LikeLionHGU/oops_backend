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
    public record Analysis(List<String> enabledAnalyzers, Boolean factCheckScreenText,
                           String captionSource, ContextReview contextReview) {

        /**
         * 자막을 어디서 가져올지. (2026-10 고도화)
         *
         *   srt  편집자가 내보낸 SRT 자막 파일을 쓴다. OCR 은 돌리지 않는다. (기본)
         *   ocr  예전처럼 화면을 캡처해 글자를 읽는다.
         *
         * OCR 은 편집 자막과 화면 속 글자(간판·메뉴판·로고)를 구분하지 못해 오탐이 많았다.
         * SRT 는 편집 자막만 정확한 글자와 시간으로 들어온다.
         * OCR 코드는 지우지 않았다. 여기를 ocr 로 바꾸면 그대로 돌아간다.
         */
        public boolean useSrtCaptions() {
            return captionSource == null || !"ocr".equalsIgnoreCase(captionSource.trim());
        }

        public ContextReview contextReviewOrDefault() {
            return contextReview != null ? contextReview : new ContextReview(null, null, null, null);
        }

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

    /**
     * 맥락 검토(2단계) 설정.
     *
     * @param windowSize       1차 선별에 한 번에 넣는 줄 수. 크면 호출이 줄고(지시문 반복이 줄어 토큰도 준다)
     *                         작으면 한 줄 한 줄을 더 꼼꼼히 본다. 기본 30.
     * @param overlap          창 사이에 겹치는 줄 수. 경계에서 문맥이 끊기는 걸 막는다. 기본 3.
     * @param verifyBatchSize  2차 검증에서 한 번에 판단할 후보 수. 기본 6.
     * @param maxCandidatesPerWindow 1차 선별이 한 창에서 올릴 수 있는 최대 후보 수.
     *                         모델이 폭주했을 때 2차 호출이 터지는 걸 막는 안전장치다. 기본 8.
     */
    public record ContextReview(Integer windowSize, Integer overlap,
                                Integer verifyBatchSize, Integer maxCandidatesPerWindow) {
        public int windowSizeOrDefault() {
            return windowSize == null || windowSize < 5 ? 30 : windowSize;
        }

        public int overlapOrDefault() {
            int o = overlap == null || overlap < 0 ? 3 : overlap;
            return Math.min(o, windowSizeOrDefault() - 1);
        }

        public int verifyBatchSizeOrDefault() {
            return verifyBatchSize == null || verifyBatchSize < 1 ? 6 : verifyBatchSize;
        }

        public int maxCandidatesPerWindowOrDefault() {
            return maxCandidatesPerWindow == null || maxCandidatesPerWindow < 1 ? 8 : maxCandidatesPerWindow;
        }
    }
}
