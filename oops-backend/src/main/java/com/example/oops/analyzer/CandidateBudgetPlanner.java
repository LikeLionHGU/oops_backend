package com.example.oops.analyzer;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Deterministic bounded opportunity allocation across a long transcript timeline. */
final class CandidateBudgetPlanner {
    static final long WINDOW_MS = 5 * 60_000L;

    private CandidateBudgetPlanner() {}

    record Selection<T>(List<T> selected, List<T> skipped) {
        Selection {
            selected = List.copyOf(selected);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * Round-robins chronological five-minute windows. Per-kind limits can reserve an
     * independent ceiling (for example, visual candidates) while sharing the total cap.
     */
    static <T> Selection<T> select(List<T> candidates, int totalLimit,
                                   ToLongFunction<T> timestampMs, Function<T, String> kind,
                                   Map<String, Integer> kindLimits) {
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(timestampMs);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(kindLimits);
        if (totalLimit < 0 || kindLimits.values().stream().anyMatch(limit -> limit == null || limit < 0))
            throw new IllegalArgumentException("Candidate budgets must be nonnegative");

        var windows = new TreeMap<Long, List<Indexed<T>>>();
        for (int i = 0; i < candidates.size(); i++) {
            T candidate = Objects.requireNonNull(candidates.get(i));
            long timestamp = timestampMs.applyAsLong(candidate);
            windows.computeIfAbsent(Math.floorDiv(timestamp, WINDOW_MS), ignored -> new ArrayList<>())
                    .add(new Indexed<>(i, timestamp, candidate, Objects.requireNonNull(kind.apply(candidate))));
        }
        windows.values().forEach(items -> items.sort(Comparator.comparingLong(Indexed<T>::timestamp).thenComparingInt(Indexed<T>::index)));

        Set<Integer> consideredIndexes = new HashSet<>();
        Set<Integer> selectedIndexes = new HashSet<>();
        Map<String, Integer> selectedByKind = new HashMap<>();
        List<T> selected = new ArrayList<>(Math.min(totalLimit, candidates.size()));
        boolean progressed;
        do {
            progressed = false;
            for (var items : windows.values()) {
                if (selected.size() >= totalLimit) break;
                for (var item : items) {
                    if (consideredIndexes.contains(item.index())) continue;
                    int kindLimit = kindLimits.getOrDefault(item.kind(), totalLimit);
                    if (selectedByKind.getOrDefault(item.kind(), 0) >= kindLimit) {
                        consideredIndexes.add(item.index()); // exhausted category; do not reconsider
                        continue;
                    }
                    selected.add(item.value());
                    consideredIndexes.add(item.index());
                    selectedIndexes.add(item.index());
                    selectedByKind.merge(item.kind(), 1, Integer::sum);
                    progressed = true;
                    break;
                }
            }
        } while (progressed && selected.size() < totalLimit);

        List<T> skipped = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) if (!selectedIndexes.contains(i)) skipped.add(candidates.get(i));
        return new Selection<>(selected, skipped);
    }

    private record Indexed<T>(int index, long timestamp, T value, String kind) {}
}
