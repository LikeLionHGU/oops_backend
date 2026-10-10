package com.example.oops.news;

/**
 * 영상 하나를 분석하는 동안 검색 API 를 몇 번 불렀는지 센다. (2026-10 고도화)
 *
 * 분석은 영상마다 한 스레드에서 차례로 돈다. 그래서 OpenAiClient 의 토큰 집계처럼
 * 스레드별로 센다. 파이프라인이 시작할 때 begin(), 끝날 때 snapshot() 을 부른다.
 *
 * 비용 단가는 2026-10 공개 가격 기준 추정치다.
 *   Perplexity Search API  web $5 / 1,000건, fast $1 / 1,000건 (요청 단위 과금, 토큰 과금 없음)
 *   Serper                 약 $1 / 1,000건 (충전 단위에 따라 $0.3~1)
 */
public final class SearchUsage {

    public static final double PERPLEXITY_USD_PER_CALL = 0.005;
    public static final double PERPLEXITY_FAST_USD_PER_CALL = 0.001;
    public static final double SERPER_USD_PER_CALL = 0.001;

    /** perplexity 호출·실패·결과수, serper 호출·실패·결과수, 뉴스 대체 호출, perplexity 중 fast 호출 */
    private static final ThreadLocal<long[]> COUNTS = ThreadLocal.withInitial(() -> new long[8]);

    private SearchUsage() {
    }

    public static void begin() {
        COUNTS.set(new long[8]);
    }

    static void perplexity(boolean ok, int results, boolean fast) {
        long[] c = COUNTS.get();
        c[0]++;
        if (fast) c[7]++;
        if (!ok) c[1]++;
        c[2] += results;
    }

    static void serper(boolean ok, int results) {
        long[] c = COUNTS.get();
        c[3]++;
        if (!ok) c[4]++;
        c[5] += results;
    }

    static void newsFallback() {
        COUNTS.get()[6]++;
    }

    public static Snapshot snapshot() {
        long[] c = COUNTS.get();
        return new Snapshot(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    public record Snapshot(long perplexityCalls, long perplexityFailures, long perplexityResults,
                           long serperCalls, long serperFailures, long serperResults,
                           long newsFallbackCalls, long perplexityFastCalls) {
        public boolean isEmpty() {
            return perplexityCalls + serperCalls + newsFallbackCalls == 0;
        }

        public double costUsd() {
            long web = perplexityCalls - perplexityFastCalls;
            return web * PERPLEXITY_USD_PER_CALL + perplexityFastCalls * PERPLEXITY_FAST_USD_PER_CALL
                    + serperCalls * SERPER_USD_PER_CALL;
        }
    }
}
