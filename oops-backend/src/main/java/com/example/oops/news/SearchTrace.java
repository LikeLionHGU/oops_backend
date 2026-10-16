package com.example.oops.news;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영상 하나를 분석하는 동안의 검색 기록을 모은다. (2026-10 고도화)
 *
 * 무엇을 검색했고(검색어), 무엇을 받았고(결과 전체), AI 가 그걸 보고 어떻게 판단했는지를
 * 한 묶음으로 남긴다. 프롬프트를 다듬을 때 "어떤 검색어 → 어떤 자료 → 왜 카드가 됐는지" 를
 * 한눈에 보려고 만들었다.
 *
 * 분석은 영상마다 한 스레드에서 차례로 돌아서 SearchUsage 처럼 스레드별로 모은다.
 *   파이프라인  begin(videoId) → analyzer(key) … → snapshot() 을 파일로 저장
 *   분석기      beginGroup(주장·주제) → (검색은 WebSearchClient 가 기록) → evidence(…) → decide(…)
 */
public final class SearchTrace {

    /** 결과 하나. 본문은 AI 에게 넘긴 길이 그대로다. */
    public record Result(String title, String url, String date, String snippet, String publisher) {}

    /** 검색 한 번 */
    public record Search(String provider, String query, String mode, int resultCount, List<Result> results) {}

    /** AI 에게 실제로 넘긴 자료 한 건 (번호는 AI 가 sources 로 돌려주는 번호다) */
    public record Evidence(int index, String title, String url, String sourceType) {}

    /** 주장·주제 하나에 대한 검색과 판단 */
    public static final class Group {
        public final String analyzer;
        public final String subject;
        public final String line;
        public final List<Search> searches = new ArrayList<>();
        public List<Evidence> evidenceToAi = List.of();
        public String verdict;
        public Double score;
        public String reason;
        public List<Integer> usedSources = List.of();
        public String outcome;

        Group(String analyzer, String subject, String line) {
            this.analyzer = analyzer;
            this.subject = subject;
            this.line = line;
        }
    }

    private static final class State {
        long videoId;
        String analyzer = "-";
        final List<Group> groups = new ArrayList<>();
        Group current;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private SearchTrace() {
    }

    public static void begin(long videoId) {
        State s = new State();
        s.videoId = videoId;
        STATE.set(s);
    }

    /** 지금 돌고 있는 분석기. 파이프라인이 분석기마다 부른다. */
    public static void analyzer(String key) {
        STATE.get().analyzer = key == null ? "-" : key;
        STATE.get().current = null;
    }

    /** 주장·주제 하나의 검색을 시작한다. 이후 검색과 판단은 이 묶음에 붙는다. */
    public static void beginGroup(String subject, String line) {
        State s = STATE.get();
        Group g = new Group(s.analyzer, subject, line);
        s.groups.add(g);
        s.current = g;
    }

    /** 검색 한 번의 결과. WebSearchClient 가 제공자별로 부른다. */
    static void search(String provider, String query, boolean recent, List<NewsSearchClient.NewsItem> items) {
        State s = STATE.get();
        if (s.current == null) {
            beginGroup(query, null);   // 묶음 없이 검색한 경우 검색어를 주제로 삼는다
        }
        List<Result> results = new ArrayList<>();
        for (NewsSearchClient.NewsItem i : items) {
            results.add(new Result(i.title(), i.link(), i.pubDate(), i.description(), i.publisher()));
        }
        s.current.searches.add(new Search(provider, query, recent ? "최근" : "전체", results.size(), results));
    }

    public static void evidence(List<Evidence> evidence) {
        Group g = STATE.get().current;
        if (g != null) g.evidenceToAi = List.copyOf(evidence);
    }

    /** AI 판정과 최종 처리. outcome 은 "카드" / "OK" / "점수 미달" 처럼 사람이 읽을 말로. */
    public static void decide(String verdict, Double score, String reason, List<Integer> usedSources, String outcome) {
        Group g = STATE.get().current;
        if (g == null) return;
        g.verdict = verdict;
        g.score = score;
        g.reason = reason;
        g.usedSources = usedSources == null ? List.of() : List.copyOf(usedSources);
        g.outcome = outcome;
    }

    /** 판정 없이 끝난 경우 (자료 없음, 줄 못 찾음 등) */
    public static void outcome(String outcome) {
        Group g = STATE.get().current;
        if (g != null && g.outcome == null) g.outcome = outcome;
    }

    public static boolean isEmpty() {
        return STATE.get().groups.isEmpty();
    }

    /** 파일로 저장할 모양 */
    public static Map<String, Object> snapshot() {
        State s = STATE.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", s.videoId);
        out.put("savedAt", OffsetDateTime.now().toString());
        int searches = s.groups.stream().mapToInt(g -> g.searches.size()).sum();
        out.put("searchCount", searches);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Group g : s.groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("analyzer", label(g.analyzer));
            m.put("subject", g.subject);
            m.put("line", g.line);
            m.put("outcome", g.outcome);
            m.put("verdict", g.verdict);
            m.put("score", g.score);
            m.put("reason", g.reason);
            m.put("usedSources", g.usedSources);
            m.put("evidenceToAi", g.evidenceToAi);
            m.put("searches", g.searches);
            groups.add(m);
        }
        out.put("groups", groups);
        return out;
    }

    private static String label(String key) {
        return switch (key) {
            case "entity-check" -> "사실 확인";
            case "context-check" -> "배경 확인";
            default -> key;
        };
    }
}
