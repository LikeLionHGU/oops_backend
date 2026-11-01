package com.example.oops.context;

import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.context.LineContextBuilder.ContextWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LineContextBuilderTest {

    final LineContextBuilder builder = new LineContextBuilder(ContextProperties.defaults());

    /** 길이 durMs 인 줄 count 개를 공백 gapMs 로 이어 붙인다. */
    static List<ContextLine> lines(int count, long durMs, long gapMs) {
        List<ContextLine> out = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < count; i++) {
            out.add(new ContextLine("seg-" + i, t, t + durMs, "줄 " + i));
            t += durMs + gapMs;
        }
        return out;
    }

    static List<String> ids(List<ContextLine> lines) {
        return lines.stream().map(ContextLine::id).toList();
    }

    @Test void discoveryCoresCoverEveryLineExactlyOnceAndStopAtTwentyLines() {
        var all = lines(45, 1_000, 0);
        var windows = builder.discoveryWindows(all);
        assertThat(windows).extracting(w -> w.core().size()).containsExactly(20, 20, 5);
        Set<String> seen = new HashSet<>();
        windows.forEach(w -> w.core().forEach(l -> assertThat(seen.add(l.id())).isTrue()));
        assertThat(seen).hasSize(45);
    }

    @Test void discoveryCoreStopsAtSixtySeconds() {
        var windows = builder.discoveryWindows(lines(20, 10_000, 0));
        assertThat(windows.get(0).core()).hasSize(6);   // 6줄 = 60초, 7줄째는 70초
        assertThat(windows).allSatisfy(w -> {
            long span = w.core().get(w.core().size() - 1).endMs() - w.core().get(0).startMs();
            assertThat(span).isLessThanOrEqualTo(60_000);
        });
    }

    @Test void baseContextTakesFiveLinesWhenTheyFillFifteenSeconds() {
        var all = lines(30, 3_000, 0);
        ContextWindow w = builder.around(all, 15, 15, false);
        assertThat(ids(w.before())).containsExactly("seg-10", "seg-11", "seg-12", "seg-13", "seg-14");
        assertThat(ids(w.after())).containsExactly("seg-16", "seg-17", "seg-18", "seg-19", "seg-20");
    }

    @Test void shortLinesAreAddedUntilTheMinimumDuration() {
        var all = lines(60, 1_000, 0);
        ContextWindow w = builder.around(all, 30, 30, false);
        assertThat(w.before()).hasSize(15);   // 5줄로는 5초뿐이라 15초까지 채운다
        assertThat(w.after()).hasSize(15);
    }

    @Test void longLinesStopAtTheMaximumDuration() {
        var all = lines(20, 10_000, 0);
        ContextWindow w = builder.around(all, 10, 10, false);
        assertThat(w.before()).hasSize(3);    // 30초까지만
        assertThat(w.after()).hasSize(3);
    }

    @Test void silenceStopsContext() {
        List<ContextLine> all = new ArrayList<>(lines(5, 2_000, 0));
        all.add(new ContextLine("after-silence", 20_000L, 22_000L, "다른 장면"));   // 앞 줄 끝(10초)과 10초 공백
        all.add(new ContextLine("next", 22_000L, 24_000L, "이어지는 말"));
        ContextWindow w = builder.around(all, 6, 6, false);
        assertThat(ids(w.before())).containsExactly("after-silence");
        ContextWindow w2 = builder.around(all, 4, 4, false);
        assertThat(w2.after()).isEmpty();
    }

    @Test void adjacentLineLongerThanMaximumIsKeptWhole() {
        List<ContextLine> all = List.of(
                new ContextLine("long", 0L, 40_000L, "아주 긴 한 줄"),
                new ContextLine("core", 40_000L, 42_000L, "대상"));
        ContextWindow w = builder.around(all, 1, 1, false);
        assertThat(ids(w.before())).containsExactly("long");
    }

    @Test void expansionUsesTenLinesAndSixtySeconds() {
        var all = lines(40, 3_000, 0);
        ContextWindow base = builder.around(all, 20, 20, false);
        ContextWindow expanded = builder.around(all, 20, 20, true);
        assertThat(base.before()).hasSize(5);
        assertThat(expanded.before()).hasSize(10);
        assertThat(expanded.expanded()).isTrue();
        var longLines = lines(40, 8_000, 0);
        assertThat(builder.around(longLines, 20, 20, true).before()).hasSize(7);   // 7줄=56초, 8줄째는 64초
    }

    @Test void videoBoundariesAreNotCrossed() {
        var all = lines(10, 3_000, 0);
        assertThat(builder.around(all, 0, 0, false).before()).isEmpty();
        assertThat(builder.around(all, 9, 9, false).after()).isEmpty();
    }

    @Test void untimedLinesUseLineCountOnly() {
        List<ContextLine> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) all.add(new ContextLine("s" + i, null, null, "줄 " + i));
        ContextWindow w = builder.around(all, 15, 15, false);
        assertThat(w.before()).hasSize(5);
        assertThat(w.after()).hasSize(5);
        assertThat(builder.around(all, 15, 15, true).before()).hasSize(10);
        assertThat(builder.discoveryWindows(all)).extracting(x -> x.core().size()).containsExactly(20, 10);
    }

    @Test void aroundIdsSpansFromFirstToLastCoreLine() {
        var all = lines(30, 3_000, 0);
        var w = builder.aroundIds(all, List.of("seg-12", "seg-10"), false).orElseThrow();
        assertThat(ids(w.core())).containsExactly("seg-10", "seg-11", "seg-12");
        assertThat(builder.aroundIds(all, List.of("none"), false)).isEmpty();
    }
}
