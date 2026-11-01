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
                         int searchCalls, int failedSearches, int skippedClaims, int invalidOutputs) {
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
        return new Result(accepted, trace, run.calls.get(), run.failed.get(), run.searches.get(),
                run.failedSearches.get(), skipped, run.invalid.get());
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
            if (!Boolean.TRUE.equals(c.specific()) || blank(c.subject()) || blank(c.searchQuery())) {
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
            if (usedIds.add(s.id())) used.add(new UsedSource(s.id(), e.quote().strip(), s.item(), s.provider()));
        }
        List<ContextLine> segments = run.lines.stream().filter(l -> c.segmentIds().contains(l.id())).toList();
        var accepted = new Accepted(c.id(), segments, c.subject(), c.claim(), c.quote(), j.reason().strip(),
                j.correction().strip(), used);
        return new VerifyOutcome(new TraceEntry("VERIFY", c.id(), c.segmentIds(), c.subject(), c.claim(), "REFUTED",
                null, searches, missing(j)), accepted);
    }

    private Optional<Judgment> judge(Run run, Claim c, List<Source> sources, boolean finalRound) {
        return run.call(FactCheckPrompts.judge(c.subject(), c.claim(), c.conditions(), c.quote(), sources, finalRound),
                Judgment.class);
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
