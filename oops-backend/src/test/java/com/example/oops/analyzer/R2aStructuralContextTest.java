package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** R2-A structural conditions under r2a-structural-v1; synthetic transcripts only. */
class R2aStructuralContextTest {
    private static ReviewInput.Segment atom(int i, long start, String text) {
        return new ReviewInput.Segment("stt-index-" + i, TimelineEventType.SPEECH, start, start + 1000, text, null);
    }

    private static ReviewInput uniform(int count) {
        List<ReviewInput.Segment> atoms = new ArrayList<>();
        for (int i = 0; i < count; i++) atoms.add(atom(i, i * 2000L, "변경 없는 대화"));
        return new ReviewInput(atoms);
    }

    private static List<String> ids(int from, int toExclusive) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) out.add("stt-index-" + i);
        return out;
    }

    private static int index(String id) { return Integer.parseInt(id.substring("stt-index-".length())); }

    private static TextReviewBatchPlanner.Batch page(ReviewInput input, int n) { return DialogueReadingPage.plan(input).get(n); }

    @Test void s0IsExactlyTheA0SelectorContext() {
        var input = uniform(200);
        String target = page(input, 1).primary().get(10).id();
        var s0 = R2aStructuralContext.expand(input, target).get("S0_A0");
        var a0 = new DialoguePageContextSelector().select(input.find(target).orElseThrow(), input).contextSegmentIds();
        assertThat(s0.theoretical()).isEqualTo(a0);
        assertThat(s0.withinLimit()).isEqualTo(a0);
        assertThat(s0.trimmed()).isEmpty();
        assertThat(s0.addedSegments()).isZero();
        assertThat(s0.status()).isEqualTo("OK");
    }

    @Test void lastPrimaryGetsTheMirroredSuffixAndTheForwardExpansions() {
        var input = uniform(200);
        var first = page(input, 0);
        int last = index(first.primary().get(first.primary().size() - 1).id());
        var result = R2aStructuralContext.expand(input, "stt-index-" + last);
        Set<String> s0 = new HashSet<>(result.get("S0_A0").theoretical());

        // suffix: segment end within 10 s of the last primary's end, step 2 s -> 5 segments
        assertThat(result.get("S1_PAGE_OVERLAP").theoretical()).containsAll(ids(last + 1, last + 6))
                .doesNotContain("stt-index-" + (last + 6));
        assertThat(result.get("S1_PAGE_OVERLAP").addedSegments()).isEqualTo(5);
        assertThat(result.get("S2_NEIGHBOR_GUARANTEE").theoretical()).containsAll(ids(last + 1, last + 6));
        assertThat(result.get("S2_NEIGHBOR_GUARANTEE").addedSegments()).isEqualTo(5);
        assertThat(result.get("S3_BOUNDARY_EXPANSION").theoretical()).containsAll(ids(last + 1, last + 11));
        assertThat(result.get("S3_BOUNDARY_EXPANSION").addedSegments()).isEqualTo(10);
        for (var e : result.values()) {
            assertThat(e.theoretical()).containsAll(s0).doesNotContain("stt-index-" + last).doesNotHaveDuplicates();
            assertThat(e.theoretical().stream().map(R2aStructuralContextTest::index).toList()).isSorted();
        }
    }

    @Test void interiorTargetGetsNothingFromS3AndNeighboursAlreadyInsideAreNotCounted() {
        var input = uniform(200);
        String target = page(input, 1).primary().get(15).id();
        var result = R2aStructuralContext.expand(input, target);
        assertThat(result.get("S3_BOUNDARY_EXPANSION").theoretical()).isEqualTo(result.get("S0_A0").theoretical());
        assertThat(result.get("S2_NEIGHBOR_GUARANTEE").addedSegments()).isZero();
    }

    @Test void firstPrimaryOfALaterPageExpandsBackwardBeyondThePrefix() {
        var input = uniform(200);
        var second = page(input, 1);
        String target = second.primary().get(0).id();
        int firstWindow = index(DialogueReadingPage.raw(second).get(0).id());
        var s3 = R2aStructuralContext.expand(input, target).get("S3_BOUNDARY_EXPANSION");
        assertThat(s3.theoretical()).containsAll(ids(firstWindow - 10, firstWindow));
        assertThat(s3.addedSegments()).isEqualTo(10);
    }

    @Test void videoStartAndEndUseOnlyExistingSegments() {
        var input = uniform(200);
        var start = R2aStructuralContext.expand(input, "stt-index-0");
        assertThat(start.get("S3_BOUNDARY_EXPANSION").theoretical().stream().map(R2aStructuralContextTest::index))
                .allMatch(i -> i > 0);
        var end = R2aStructuralContext.expand(input, "stt-index-199");
        for (var e : end.values())
            assertThat(e.theoretical().stream().map(R2aStructuralContextTest::index)).allMatch(i -> i < 199);
        assertThat(end.get("S1_PAGE_OVERLAP").addedSegments()).isZero();
    }

    @Test void overLimitWindowsDropAddedSegmentsFarthestFirstAndKeepS0() {
        List<ReviewInput.Segment> atoms = new ArrayList<>();
        for (int i = 0; i < 30; i++) atoms.add(atom(i, i * 2000L, "짧은 말"));
        for (int i = 30; i < 50; i++) atoms.add(atom(i, i * 2000L, "길".repeat(2500)));
        var input = new ReviewInput(atoms);
        var first = page(input, 0);
        String target = first.primary().get(first.primary().size() - 3).id();
        var result = R2aStructuralContext.expand(input, target);
        var s3 = result.get("S3_BOUNDARY_EXPANSION");

        assertThat(s3.status()).isEqualTo("TRIMMED");
        assertThat(s3.trimmed()).isNotEmpty();
        assertThat(s3.inputCodePoints()).isLessThanOrEqualTo(R2aStructuralContext.LIMIT);
        assertThat(s3.withinLimit()).containsAll(result.get("S0_A0").theoretical());
        Set<String> rebuilt = new HashSet<>(s3.withinLimit());
        rebuilt.addAll(s3.trimmed());
        assertThat(rebuilt).isEqualTo(new HashSet<>(s3.theoretical()));
        int t = index(target);
        int nearestTrimmed = s3.trimmed().stream().mapToInt(id -> Math.abs(index(id) - t)).min().orElseThrow();
        int farthestKeptAdded = s3.withinLimit().stream().filter(id -> !result.get("S0_A0").theoretical().contains(id))
                .mapToInt(id -> Math.abs(index(id) - t)).max().orElse(0);
        assertThat(nearestTrimmed).isGreaterThan(farthestKeptAdded);
    }

    @Test void s0OverTheLimitIsReportedNotTrimmed() {
        var base = List.of(atom(0, 0, "가"), atom(1, 2000, "나"));
        var added = List.of(atom(2, 4000, "다"));
        var fit = R2aStructuralContext.fit(base, added, base.get(0), s -> index(s.id()), w -> w.size() * 100, 150);
        assertThat(fit.status()).isEqualTo("S0_OVER_LIMIT");
        assertThat(fit.kept()).isEmpty();
        assertThat(fit.removed()).isEmpty();
    }

    @Test void tiesInDistanceDropTheLaterSegmentFirst() {
        var target = atom(5, 10_000, "대상");
        var base = List.of(target);
        var added = List.of(atom(3, 6000, "앞"), atom(7, 14_000, "뒤"));
        var fit = R2aStructuralContext.fit(base, added, target, s -> index(s.id()), w -> w.size() * 100, 250);
        assertThat(fit.status()).isEqualTo("TRIMMED");
        assertThat(fit.removed()).extracting(ReviewInput.Segment::id).containsExactly("stt-index-7");
        assertThat(fit.kept()).extracting(ReviewInput.Segment::id).containsExactly("stt-index-3");
    }

    @Test void strataMarkFirstAndLastThreePrimariesOfEachPageAsEdge() {
        var input = uniform(200);
        var strata = R2aStructuralContext.strata(input);
        for (var page : DialogueReadingPage.plan(input)) {
            var primary = page.primary();
            for (int i = 0; i < primary.size(); i++) {
                boolean edge = i < 3 || i >= primary.size() - 3;
                assertThat(strata.get(primary.get(i).id())).isEqualTo(edge ? "EDGE" : "INTERIOR");
            }
        }
        assertThat(strata).hasSize(200);
    }
}
