package com.example.oops.factcheck;

import com.example.oops.context.ContextProperties;
import com.example.oops.context.ContextReviewEngine;
import com.example.oops.context.LineContextBuilder;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.context.LineContextBuilder.ContextWindow;
import com.example.oops.factcheck.FactCheckPrompts.ExtractedClaim;
import com.example.oops.factcheck.FactCheckPrompts.Extraction;
import com.example.oops.factcheck.FactCheckPrompts.Judgment;
import com.example.oops.factcheck.FactCheckPrompts.Source;
import com.example.oops.factcheck.FactCheckPrompts.SourceEvidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 사실대조 실행과 서버 검증. (redesign-v3, docs/redesign-v3.md 6절)
 *
 *   1. 주장 추출: 대상 묶음마다 별도 GPT 요청. 맥락 확인 결과와 무관하게 돈다.
 *      대상을 특정하지 못한 주장은 검색하지 않는다(내부 기록 NOT_SPECIFIC).
 *   2. Perplexity 1차 검색 → GPT 가 같은 대상·시점·조건, 직접 지지/반박, 자료 충돌을 평가한다.
 *   3. 판단 근거가 부족하고 보완할 사실이 분명할 때만 서버가 확인한 뒤 Serper 를 주장당 최대 1회.
 *      1차가 장애·키 없음이면 Serper 를 대신 쓰고, 그것으로 그 주장의 보조 1회를 다 쓴 것으로 센다.
 *   4. REFUTED 만 카드로 낸다. 검색 실패·관련 결과 없음·근거 부족·자료 충돌은 내부에서 구분해 기록하고 제외한다.
 *
 * 검증: 주장 인용이 실제 구간 원문에 있는지, 근거가 실제로 받은 자료의 연속 문자열인지.
 * 근거를 고르지 못하면 상위 검색 결과를 자동으로 붙이지 않는다.
 *
 * 사실 주장이 아닌 것은 서버에서도 거른다 (470번 영상 시험 후 추가):
 *   - 대상이 이름이 아닌 말(화자·발언자·남편 …) → NOT_SPECIFIC, 질문 형태의 주장 → NOT_ASSERTION
 *   - 대조 결과 반박이어도, 발언 원문이 그 내용을 단정하지 않으면(퀴즈 답·문맥에서 덧붙인 해석) NOT_ASSERTED,
 *     뒤 문맥에서 바로 고쳐 말했으면 CORRECTED_IN_VIDEO 로 제외
 *   - 반박 근거 자료에 주장 대상이 나오지 않으면 그 자료는 버리고, 남는 근거가 없으면 INVALID
 */
public final class FactCheckEngine {

    public enum Verdict { SUPPORTED, REFUTED, CONFLICT, INSUFFICIENT }

    /** 내부 기록 한 줄. 사용자에게 보여주지 않는다. */
    public record TraceEntry(String stage, String claimId, List<String> segmentIds, String subject, String claim,
                             String outcome, String note, List<SearchTrace> searches, List<String> missingInformation) {}

    public record SearchTrace(String provider, String query, String status, int results, List<String> urls) {}

    public record UsedSource(String sourceId, String quote, Search.Item item, String provider) {}

    /** 사용자에게 보여줄 사실대조 결과 (REFUTED) */
    public record Accepted(String claimId, List<ContextLine> segments, String subject, String claim, String claimQuote,
                           String reason, String correction, List<UsedSource> sources) {}

    public record Result(List<Accepted> accepted, List<TraceEntry> trace, int modelCalls, int failedCalls,
                         int searchCalls, int failedSearches, int skippedClaims, int invalidOutputs,
                         Map<String, Integer> searchesByProvider) {
        public Result(List<Accepted> accepted, List<TraceEntry> trace, int modelCalls, int failedCalls,
                      int searchCalls, int failedSearches, int skippedClaims, int invalidOutputs) {
            this(accepted, trace, modelCalls, failedCalls, searchCalls, failedSearches, skippedClaims, invalidOutputs, Map.of());
        }

        public boolean partial() {
            return failedCalls > 0 || failedSearches > 0 || skippedClaims > 0;
        }
    }

    private final FactCheckProperties p;
    private final LineContextBuilder contexts;
    private final ContextReviewEngine.Model model;
    private final Search.Provider primary;
    private final Search.Provider secondary;
    private final Executor executor;

    public FactCheckEngine(FactCheckProperties properties, ContextProperties contextProperties,
                           ContextReviewEngine.Model model, Search.Provider primary, Search.Provider secondary,
                           Executor executor) {
        this.p = properties;
        this.contexts = new LineContextBuilder(contextProperties);
        this.model = model;
        this.primary = primary;
        this.secondary = secondary;
        this.executor = executor;
    }

    private record Claim(String id, List<String> segmentIds, String quote, String subject, String claim,
                         List<String> conditions, String query) {}

    private final class Run {
        final List<ContextLine> lines;
        final Runnable checkActive;
        final AtomicInteger calls = new AtomicInteger(), failed = new AtomicInteger(), searches = new AtomicInteger(),
                failedSearches = new AtomicInteger(), invalid = new AtomicInteger();
        /** 업체별 실제 요청 수(비용 계산용). 키 없음(DISABLED)은 세지 않는다. */
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> byProvider = new java.util.concurrent.ConcurrentHashMap<>();

        Run(List<ContextLine> lines, Runnable checkActive) {
            this.lines = lines;
            this.checkActive = checkActive;
        }

        <T> Optional<T> call(FactCheckPrompts.Prompt prompt, Class<T> type) {
            checkActive.run();
            calls.incrementAndGet();
            Optional<T> out = model.call(prompt.system(), prompt.user(), type);
            if (out == null) out = Optional.empty();
            if (out.isEmpty()) failed.incrementAndGet();
            return out;
        }

        Search.Outcome search(Search.Provider provider, String query) {
            checkActive.run();
            if (!provider.isEnabled()) return Search.Outcome.disabled(provider.name(), query);
            searches.incrementAndGet();
            byProvider.computeIfAbsent(provider.name(), k -> new AtomicInteger()).incrementAndGet();
            Search.Outcome out = provider.search(query, p.resultsPerSearch());
            if (out == null) out = Search.Outcome.failed(provider.name(), query, "응답 없음");
            if (out.status() == Search.Status.FAILED) failedSearches.incrementAndGet();
            return out;
        }
    }

    private record ExtractOutcome(List<TraceEntry> trace, List<ExtractedClaim> valid) {}

    private record VerifyOutcome(TraceEntry trace, Accepted accepted) {}

    public Result run(List<ContextLine> lines, Runnable checkActive) {
        Run run = new Run(List.copyOf(lines), checkActive == null ? () -> { } : checkActive);
        List<TraceEntry> trace = new ArrayList<>();
        if (lines.isEmpty()) return new Result(List.of(), List.of(), 0, 0, 0, 0, 0, 0);

        // 1. 주장 추출 (묶음끼리 동시에)
        Map<String, Claim> claims = new LinkedHashMap<>();
        int seq = 0;
        for (ExtractOutcome o : parallel(contexts.discoveryWindows(run.lines), w -> extract(run, w))) {
            trace.addAll(o.trace());
            for (ExtractedClaim c : o.valid()) {
                Set<String> idSet = new HashSet<>(c.segmentIds());
                List<String> ordered = run.lines.stream().map(ContextLine::id).filter(idSet::contains).toList();
                String key = String.join(",", ordered) + "|" + normalize(c.claim());
                if (!claims.containsKey(key)) {
                    String id = "f" + (++seq);
                    claims.put(key, new Claim(id, ordered, c.claimQuote().strip(), c.subject().strip(), c.claim().strip(),
                            c.conditions() == null ? List.of() : c.conditions().stream().filter(x -> x != null && !x.isBlank()).toList(),
                            c.searchQuery().strip()));
                }
            }
        }
        List<Claim> list = new ArrayList<>(claims.values());
        int skipped = 0;
        if (list.size() > p.maxClaims()) {
            skipped = list.size() - p.maxClaims();
            for (Claim c : list.subList(p.maxClaims(), list.size())) {
                trace.add(new TraceEntry("EXTRACT", c.id(), c.segmentIds(), c.subject(), c.claim(), "SKIPPED_BUDGET",
                        "영상당 주장 상한(" + p.maxClaims() + ") 초과", List.of(), List.of()));
            }
            list = new ArrayList<>(list.subList(0, p.maxClaims()));
        }

        // 2~4. 주장마다 검색·대조 (주장끼리 동시에)
        List<Accepted> accepted = new ArrayList<>();
        for (VerifyOutcome o : parallel(list, c -> verify(run, c))) {
            trace.add(o.trace());
            if (o.accepted() != null) accepted.add(o.accepted());
        }
        Map<String, Integer> byProvider = new java.util.TreeMap<>();
        run.byProvider.forEach((k, v) -> byProvider.put(k, v.get()));
        return new Result(accepted, trace, run.calls.get(), run.failed.get(), run.searches.get(),
                run.failedSearches.get(), skipped, run.invalid.get(), Map.copyOf(byProvider));
    }

    private ExtractOutcome extract(Run run, ContextWindow window) {
        List<TraceEntry> trace = new ArrayList<>();
        List<ExtractedClaim> valid = new ArrayList<>();
        var out = run.call(FactCheckPrompts.extract(window), Extraction.class);
        String range = window.core().get(0).id() + "~" + window.core().get(window.core().size() - 1).id();
        if (out.isEmpty()) {
            trace.add(new TraceEntry("EXTRACT", null, List.of(), null, null, "FAILED", "주장 추출 호출 실패: " + range,
                    List.of(), List.of()));
            return new ExtractOutcome(trace, valid);
        }
        Set<String> core = window.coreIds();
        for (ExtractedClaim c : out.get().claims() == null ? List.<ExtractedClaim>of() : out.get().claims()) {
            if (c == null) continue;
            List<String> ids = c.segmentIds() == null ? List.of() : c.segmentIds().stream().filter(x -> x != null).map(String::strip).toList();
            String problem = null;
            if (ids.isEmpty() || !core.containsAll(ids)) problem = "구간이 검토 대상 밖이거나 비어 있음";
            else if (blank(c.claimQuote()) || ids.stream().noneMatch(id -> window.line(id)
                    .map(l -> squash(l.text()).contains(squash(c.claimQuote()))).orElse(false))) problem = "원문에 없는 인용";
            else if (blank(c.claim())) problem = "주장 내용 없음";
            if (problem != null) {
                run.invalid.incrementAndGet();
                trace.add(new TraceEntry("EXTRACT", null, ids, c.subject(), c.claim(), "INVALID", problem, List.of(), List.of()));
                continue;
            }
            if (isQuestion(c.claim())) {
                trace.add(new TraceEntry("EXTRACT", null, ids, c.subject(), c.claim(), "NOT_ASSERTION",
                        "질문이라 사실 주장이 아님", List.of(), List.of()));
                continue;
            }
            if (!Boolean.TRUE.equals(c.specific()) || blank(c.subject()) || blank(c.searchQuery()) || genericSubject(c.subject())) {
                // 모르는 대상을 추측해 검색하지 않는다
                trace.add(new TraceEntry("EXTRACT", null, ids, c.subject(), c.claim(), "NOT_SPECIFIC",
                        "대상을 특정할 수 없어 검색하지 않음", List.of(), List.of()));
                continue;
            }
            valid.add(new ExtractedClaim(ids, c.claimQuote(), c.subject(), c.claim(), c.conditions(), true, c.searchQuery()));
        }
        return new ExtractOutcome(trace, valid);
    }

    private VerifyOutcome verify(Run run, Claim c) {
        List<SearchTrace> searches = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean secondaryUsed = false;

        Search.Outcome first = run.search(primary, c.query());
        searches.add(trace(first));
        if (first.unavailable()) {
            // 1차 장애·키 없음: 보조를 대신 쓴다. 이것이 그 주장의 보조 1회다.
            secondaryUsed = true;
            Search.Outcome substitute = run.search(secondary, c.query());
            searches.add(trace(substitute));
            if (substitute.unavailable()) return outcome(c, "SEARCH_FAILED", "검색을 수행하지 못함", searches, List.of());
            addSources(sources, seen, substitute);
        } else {
            addSources(sources, seen, first);
        }
        if (sources.isEmpty()) return outcome(c, "NO_RESULTS", "관련 검색 결과 없음", searches, List.of());

        var judged = judge(run, c, sources, false);
        // (아래 두 번째 대조도 같은 앞뒤 문맥을 본다)
        if (judged.isEmpty()) return outcome(c, "FAILED", "자료 대조 호출 실패", searches, List.of());
        Judgment j = judged.get();
        Optional<String> problem = judgmentProblem(c, j, sources);
        if (problem.isPresent()) {
            run.invalid.incrementAndGet();
            return outcome(c, "INVALID", problem.get(), searches, missing(j));
        }
        Verdict verdict = Verdict.valueOf(j.verdict().strip());

        if (verdict == Verdict.INSUFFICIENT && !secondaryUsed && j.supplement() != null) {
            String query = j.supplement().query() == null ? "" : j.supplement().query().strip();
            boolean ok = !query.isEmpty() && query.length() <= 120 && !blank(j.supplement().purpose())
                    && !normalize(query).equals(normalize(c.query())) && secondary.isEnabled();
            if (ok) {
                Search.Outcome extra = run.search(secondary, query);
                searches.add(trace(extra));
                int before = sources.size();
                addSources(sources, seen, extra);
                if (sources.size() > before) {
                    var second = judge(run, c, sources, true);
                    if (second.isEmpty()) return outcome(c, "FAILED", "보조 검색 후 자료 대조 호출 실패", searches, missing(j));
                    j = second.get();
                    problem = judgmentProblem(c, j, sources);
                    if (problem.isPresent()) {
                        run.invalid.incrementAndGet();
                        return outcome(c, "INVALID", problem.get(), searches, missing(j));
                    }
                    verdict = Verdict.valueOf(j.verdict().strip());
                }
            }
        }

        if (verdict == Verdict.REFUTED && !Boolean.TRUE.equals(j.asserted()))
            return outcome(c, "NOT_ASSERTED", "발언 원문이 이 내용을 사실로 단정하지 않음(질문·퀴즈 답·덧붙인 해석)", searches, missing(j));
        if (verdict == Verdict.REFUTED && Boolean.TRUE.equals(j.correctedInVideo()))
            return outcome(c, "CORRECTED_IN_VIDEO", "영상 안에서 바로 고쳐 말함", searches, missing(j));

        if (verdict != Verdict.REFUTED) {
            String outcome = switch (verdict) {
                case SUPPORTED -> "SUPPORTED";
                case CONFLICT -> "CONFLICT";
                default -> "INSUFFICIENT";
            };
            return outcome(c, outcome, null, searches, missing(j));
        }
        Map<String, Source> byId = new LinkedHashMap<>();
        sources.forEach(s -> byId.put(s.id(), s));
        List<UsedSource> used = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (SourceEvidence e : j.evidence()) {
            Source s = byId.get(e.sourceId().strip());
            // 주장 대상이 나오지 않는 자료는 근거로 붙이지 않는다 (예: 같은 단어가 메뉴에 있는 식당 소개 페이지)
            if (!mentions(s.item().title() + " " + s.item().snippet(), c.subject())) continue;
            if (usedIds.add(s.id())) used.add(new UsedSource(s.id(), e.quote().strip(), s.item(), s.provider()));
        }
        if (used.isEmpty()) {
            run.invalid.incrementAndGet();
            return outcome(c, "INVALID", "반박 근거 자료에 주장 대상이 나오지 않음", searches, missing(j));
        }
        List<ContextLine> segments = run.lines.stream().filter(l -> c.segmentIds().contains(l.id())).toList();
        var accepted = new Accepted(c.id(), segments, c.subject(), c.claim(), c.quote(), j.reason().strip(),
                j.correction().strip(), used);
        return new VerifyOutcome(new TraceEntry("VERIFY", c.id(), c.segmentIds(), c.subject(), c.claim(), "REFUTED",
                null, searches, missing(j)), accepted);
    }

    private Optional<Judgment> judge(Run run, Claim c, List<Source> sources, boolean finalRound) {
        int first = -1, last = -1;
        for (int i = 0; i < run.lines.size(); i++) {
            if (c.segmentIds().contains(run.lines.get(i).id())) {
                if (first < 0) first = i;
                last = i;
            }
        }
        List<ContextLine> before = first < 0 ? List.of() : run.lines.subList(Math.max(0, first - JUDGE_BEFORE), first);
        List<ContextLine> after = last < 0 ? List.of() : run.lines.subList(last + 1, Math.min(run.lines.size(), last + 1 + JUDGE_AFTER));
        return run.call(FactCheckPrompts.judge(c.subject(), c.claim(), c.conditions(), c.quote(), before, after,
                sources, finalRound), Judgment.class);
    }

    /** 대조할 때 보여줄 앞뒤 줄 수. 뒤를 더 길게 보는 이유: 정답 공개·정정은 보통 몇 줄 뒤에 나온다. */
    static final int JUDGE_BEFORE = 3, JUDGE_AFTER = 6;

    /** 이름이 아닌 대상. 검색하면 같은 단어가 들어간 엉뚱한 사람·작품이 나온다. */
    private static final Set<String> GENERIC_SUBJECTS = Set.of(
            "화자", "발언자", "말하는사람", "진행자", "출연자", "게스트", "패널", "나", "저", "본인", "자신", "우리", "저희",
            "남편", "아내", "부인", "와이프", "엄마", "아빠", "어머니", "아버지", "가족", "친구", "지인", "동료", "상대", "상대방",
            "그", "그녀", "그사람", "이사람", "그분", "이분", "누군가", "사람들");

    static boolean genericSubject(String subject) {
        String s = normalize(subject);
        if (s.isEmpty()) return true;
        if (GENERIC_SUBJECTS.contains(s)) return true;
        // "화자의 남편", "발언자의 방송 출연 일정" 처럼 이름이 아닌 말로 시작하는 대상
        for (String g : List.of("화자", "발언자", "말하는사람", "출연자", "진행자", "본인", "자신"))
            if (s.startsWith(g)) return true;
        return false;
    }

    static boolean isQuestion(String claim) {
        if (claim == null) return false;
        String s = claim.strip();
        return s.endsWith("?") || s.matches(".*(묻고 있다|묻는다|질문한다|무엇인가|무엇인지|어디인가|누구인가|몇 \\S+인가)\\.?$");
    }

    /** 자료가 주장 대상을 다루는가. 대상 이름(띄어쓰기 무시) 또는 두 글자 이상 낱말 중 하나라도 나오면 다룬다고 본다. */
    static boolean mentions(String text, String subject) {
        String t = normalize(text), s = normalize(subject);
        if (s.isEmpty()) return false;
        if (t.contains(s)) return true;
        for (String w : subject.split("[\\s·,/()]+")) {
            String n = normalize(w);
            if (n.length() >= 2 && !GENERIC_SUBJECTS.contains(n) && t.contains(n)) return true;
        }
        return false;
    }

    private static Optional<String> judgmentProblem(Claim c, Judgment j, List<Source> sources) {
        Verdict verdict;
        try {
            verdict = Verdict.valueOf(String.valueOf(j.verdict()).strip());
        } catch (IllegalArgumentException e) {
            return Optional.of("알 수 없는 판정: " + j.verdict());
        }
        Map<String, Source> byId = new LinkedHashMap<>();
        sources.forEach(s -> byId.put(s.id(), s));
        List<SourceEvidence> evidence = j.evidence() == null ? List.of() : j.evidence();
        for (SourceEvidence e : evidence) {
            if (e == null || e.sourceId() == null) return Optional.of("근거 자료 ID 없음");
            Source s = byId.get(e.sourceId().strip());
            if (s == null) return Optional.of("제공하지 않은 자료 ID: " + e.sourceId());
            if (blank(e.quote()) || !squash(s.item().title() + " " + s.item().snippet()).contains(squash(e.quote())))
                return Optional.of("자료에 없는 인용: " + e.sourceId());
        }
        if (verdict != Verdict.INSUFFICIENT && evidence.isEmpty()) return Optional.of("판정 근거 인용 없음");
        if (verdict == Verdict.REFUTED && (blank(j.reason()) || blank(j.correction())))
            return Optional.of("반박 판정에 이유·확인 내용 없음");
        return Optional.empty();
    }

    /** 같은 주소(추적 값 제거)와 같은 원출처 제목을 한 번만 넣는다. */
    private static void addSources(List<Source> into, Set<String> seen, Search.Outcome outcome) {
        for (Search.Item item : outcome.items()) {
            String urlKey = "u:" + urlKey(item.url());
            String titleKey = "t:" + normalize(item.title());
            boolean dupTitle = titleKey.length() > 12 && seen.contains(titleKey);
            if (seen.contains(urlKey) || dupTitle) continue;
            seen.add(urlKey);
            if (titleKey.length() > 12) seen.add(titleKey);
            into.add(new Source("s" + (into.size() + 1), item, outcome.provider()));
        }
    }

    static String urlKey(String url) {
        if (url == null) return "";
        String u = url.trim().replaceAll("#.*$", "");
        int q = u.indexOf('?');
        if (q < 0) return u.replaceAll("/$", "").toLowerCase(Locale.ROOT);
        String base = u.substring(0, q).replaceAll("/$", "");
        String kept = java.util.Arrays.stream(u.substring(q + 1).split("&")).filter(x -> !x.isBlank())
                .filter(x -> !x.matches("(?i)(utm_[^=]*|fbclid|gclid|ref|ref_src|from)(=.*)?")).sorted()
                .collect(java.util.stream.Collectors.joining("&"));
        return (kept.isEmpty() ? base : base + "?" + kept).toLowerCase(Locale.ROOT);
    }

    private static SearchTrace trace(Search.Outcome o) {
        return new SearchTrace(o.provider(), o.query(), o.status().name(), o.items().size(),
                o.items().stream().map(Search.Item::url).toList());
    }

    private static List<String> missing(Judgment j) {
        return j == null || j.missingInformation() == null ? List.of()
                : j.missingInformation().stream().filter(x -> x != null && !x.isBlank()).toList();
    }

    private static VerifyOutcome outcome(Claim c, String outcome, String note, List<SearchTrace> searches, List<String> missing) {
        return new VerifyOutcome(new TraceEntry("VERIFY", c.id(), c.segmentIds(), c.subject(), c.claim(), outcome, note,
                searches, missing), null);
    }

    private <T, R> List<R> parallel(List<T> items, Function<T, R> fn) {
        List<CompletableFuture<R>> futures = items.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> fn.apply(item), executor)).toList();
        List<R> out = new ArrayList<>();
        RuntimeException first = null;
        for (var f : futures) {
            try {
                out.add(f.join());
            } catch (CompletionException | CancellationException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (first == null || cause instanceof CancellationException)
                    first = cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
            }
        }
        if (first != null) throw first;
        return out;
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
