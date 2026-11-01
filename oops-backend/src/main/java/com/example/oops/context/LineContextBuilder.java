package com.example.oops.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 줄 단위 + 시간 상·하한 혼합 문맥 구성. (redesign-v3, docs/redesign-v3.md 2절)
 *
 * - "줄"은 전사 구간 하나다. 줄은 절대 자르지 않는다.
 * - 후보 탐색은 연속 줄 묶음(대상) 단위로 하고, 후보는 대상 줄 안에서만 만든다. 대상끼리 겹치지 않는다.
 * - 문맥은 대상 앞뒤에 줄을 붙이되, 시간이 있으면 최소 길이는 채우고 최대 길이는 넘기지 않는다.
 * - 긴 공백(침묵) 너머의 줄은 붙이지 않는다. 영상 처음·끝을 넘지 않는다.
 * - 바로 옆 한 줄이 최대 길이보다 길면 그 한 줄은 통째로 넣는다.
 * - 시간이 없는 입력은 줄 수만 쓴다. 가짜 시각을 만들지 않는다.
 * - 확대는 expanded=true 로 한 번만 한다(반복 여부는 호출하는 쪽이 지킨다).
 */
public final class LineContextBuilder {

    private final ContextProperties p;

    public LineContextBuilder(ContextProperties properties) {
        this.p = properties;
    }

    public record ContextLine(String id, Long startMs, Long endMs, String text) {
        public ContextLine {
            if (id == null || id.isBlank() || text == null) throw new IllegalArgumentException("Invalid context line");
            if ((startMs == null) != (endMs == null) || (startMs != null && (startMs < 0 || endMs < startMs)))
                throw new IllegalArgumentException("Invalid context line time: " + id);
        }

        public boolean timed() {
            return startMs != null;
        }
    }

    public record ContextWindow(List<ContextLine> before, List<ContextLine> core, List<ContextLine> after,
                                boolean expanded) {
        public ContextWindow {
            before = List.copyOf(before);
            core = List.copyOf(core);
            after = List.copyOf(after);
            if (core.isEmpty()) throw new IllegalArgumentException("Empty core");
        }

        public List<ContextLine> all() {
            List<ContextLine> all = new ArrayList<>(before);
            all.addAll(core);
            all.addAll(after);
            return all;
        }

        public Set<String> ids() {
            Set<String> ids = new LinkedHashSet<>();
            all().forEach(l -> ids.add(l.id()));
            return ids;
        }

        public Set<String> coreIds() {
            Set<String> ids = new LinkedHashSet<>();
            core.forEach(l -> ids.add(l.id()));
            return ids;
        }

        public Optional<ContextLine> line(String id) {
            return all().stream().filter(l -> l.id().equals(id)).findFirst();
        }
    }

    /** 후보 탐색 대상 묶음과 각 묶음의 기본 문맥. 모든 줄이 정확히 한 묶음의 대상에 들어간다. */
    public List<ContextWindow> discoveryWindows(List<ContextLine> lines) {
        List<ContextWindow> windows = new ArrayList<>();
        boolean timed = allTimed(lines);
        int i = 0;
        while (i < lines.size()) {
            int from = i;
            long start = timed ? lines.get(from).startMs() : 0;
            int to = from;
            while (to + 1 < lines.size() && to + 1 - from < p.discoveryLines()
                    && (!timed || lines.get(to + 1).endMs() - start <= p.discoveryMaxMs())) {
                to++;
            }
            windows.add(around(lines, from, to, false));
            i = to + 1;
        }
        return windows;
    }

    /** 지정한 줄들을 대상으로 한 문맥. 대상은 가장 앞 줄부터 가장 뒤 줄까지의 연속 구간이다. */
    public Optional<ContextWindow> aroundIds(List<ContextLine> lines, Collection<String> coreIds, boolean expanded) {
        int first = -1, last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (coreIds.contains(lines.get(i).id())) {
                if (first < 0) first = i;
                last = i;
            }
        }
        return first < 0 ? Optional.empty() : Optional.of(around(lines, first, last, expanded));
    }

    public ContextWindow around(List<ContextLine> lines, int from, int to, boolean expanded) {
        if (from < 0 || to >= lines.size() || from > to) throw new IllegalArgumentException("Invalid core range");
        boolean timed = allTimed(lines);
        int n = expanded ? p.expandedLines() : p.contextLines();
        long max = expanded ? p.expandedMaxMs() : p.contextMaxMs();
        List<ContextLine> core = lines.subList(from, to + 1);
        return new ContextWindow(before(lines, from, timed, n, max), core, after(lines, from, to, timed, n, max), expanded);
    }

    private List<ContextLine> before(List<ContextLine> lines, int from, boolean timed, int n, long max) {
        List<ContextLine> picked = new ArrayList<>();
        if (!timed) {
            for (int j = from - 1; j >= 0 && picked.size() < n; j--) picked.add(lines.get(j));
            Collections.reverse(picked);
            return picked;
        }
        long anchor = lines.get(from).startMs();
        long boundary = anchor;
        for (int j = from - 1; j >= 0 && picked.size() < hardCap(n); j--) {
            ContextLine line = lines.get(j);
            if (boundary - line.endMs() > p.silenceGapMs()) break;
            boolean needCount = picked.size() < n;
            boolean needTime = anchor - boundary < p.contextMinMs();
            if (!needCount && !needTime) break;
            long span = anchor - line.startMs();
            if (span > max) {
                if (picked.isEmpty()) picked.add(line);   // 바로 옆 긴 한 줄은 통째로
                break;
            }
            picked.add(line);
            boundary = Math.min(boundary, line.startMs());
        }
        Collections.reverse(picked);
        return picked;
    }

    private List<ContextLine> after(List<ContextLine> lines, int from, int to, boolean timed, int n, long max) {
        List<ContextLine> picked = new ArrayList<>();
        if (!timed) {
            for (int j = to + 1; j < lines.size() && picked.size() < n; j++) picked.add(lines.get(j));
            return picked;
        }
        long anchor = lines.subList(from, to + 1).stream().mapToLong(ContextLine::endMs).max().orElseThrow();
        long boundary = anchor;
        for (int j = to + 1; j < lines.size() && picked.size() < hardCap(n); j++) {
            ContextLine line = lines.get(j);
            if (line.startMs() - boundary > p.silenceGapMs()) break;
            boolean needCount = picked.size() < n;
            boolean needTime = boundary - anchor < p.contextMinMs();
            if (!needCount && !needTime) break;
            long span = line.endMs() - anchor;
            if (span > max) {
                if (picked.isEmpty()) picked.add(line);
                break;
            }
            picked.add(line);
            boundary = Math.max(boundary, line.endMs());
        }
        return picked;
    }

    /** 최소 길이를 채우느라 줄이 끝없이 붙는 것을 막는다(아주 짧은 줄이 이어질 때). */
    private static int hardCap(int n) {
        return Math.max(4, n * 4);
    }

    private static boolean allTimed(List<ContextLine> lines) {
        return !lines.isEmpty() && lines.stream().allMatch(ContextLine::timed);
    }
}
