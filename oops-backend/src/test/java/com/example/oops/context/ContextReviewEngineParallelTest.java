package com.example.oops.context;

import com.example.oops.context.ContextModelOutput.Discovery;
import com.example.oops.context.ContextModelOutput.DiscoveryCandidate;
import com.example.oops.context.ContextModelOutput.Evidence;
import com.example.oops.context.ContextModelOutput.Gate;
import com.example.oops.context.ContextModelOutput.GateResult;
import com.example.oops.context.ContextModelOutput.Review;
import com.example.oops.context.ContextModelOutput.ReviewResult;
import com.example.oops.context.LineContextBuilder.ContextLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 병렬로 돌려도 순서대로 돌린 것과 같은 결과가 나오는지, 실제로 동시에 호출하는지 본다. */
class ContextReviewEngineParallelTest {

    static final Pattern CORE = Pattern.compile("## 검토 대상\\n\\[(stt-\\d+)]");
    static final Pattern CAND = Pattern.compile("# 후보 (c\\d+)");

    /** 상태 없이 입력만 보고 답하는 모델. 동시 호출 수를 잰다. */
    static final class StatelessModel implements ContextReviewEngine.Model {
        final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger(), calls = new AtomicInteger();
        final long delayMs;

        StatelessModel(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public <T> Optional<T> call(String system, String user, Class<T> type) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            calls.incrementAndGet();
            try {
                Thread.sleep(delayMs);
                Object out;
                if (type == Discovery.class) {
                    Matcher m = CORE.matcher(user);
                    m.find();
                    out = new Discovery(List.of(), List.of(new DiscoveryCandidate("G13-C01", List.of(m.group(1)), "공격 표현"),
                            new DiscoveryCandidate("G20-C01", List.of(m.group(1)), "집단 규정")));
                } else if (type == Gate.class) {
                    List<GateResult> results = new ArrayList<>();
                    Matcher m = CAND.matcher(user);
                    while (m.find()) {
                        String id = m.group(1);
                        String seg = segmentAfter(user, id);
                        results.add(new GateResult(id, "PROCEED", "확인됨", List.of(new Evidence(seg, "발언")),
                                List.of("표현 검토"), List.of(), List.of(), false));
                    }
                    out = new Gate(results);
                } else {
                    List<ReviewResult> results = new ArrayList<>();
                    Matcher m = CAND.matcher(user);
                    while (m.find()) {
                        String id = m.group(1);
                        results.add(new ReviewResult(id, "REVIEW", "검토 이유", null,
                                List.of(new Evidence(segmentAfter(user, id), "발언")), List.of(), List.of()));
                    }
                    out = new Review(results);
                }
                return Optional.of(type.cast(out));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        static String segmentAfter(String user, String candidateId) {
            int at = user.indexOf("# 후보 " + candidateId);
            Matcher m = CORE.matcher(user);
            m.find(at);
            return m.group(1);
        }
    }

    static List<ContextLine> lines(int n) {
        List<ContextLine> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ContextLine("stt-" + i, i * 2_000L, i * 2_000L + 1_500, "발언 " + i));
        return out;
    }

    @Test void parallelRunMatchesSequentialRunAndActuallyOverlaps() {
        var all = lines(100);   // 후보 탐색 묶음 5개
        var sequential = new ContextReviewEngine(ContextProperties.defaults(), new StatelessModel(0))
                .run(all, ContextReviewEngineTest.CRITERIA, null);

        var model = new StatelessModel(30);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var parallel = new ContextReviewEngine(ContextProperties.defaults(), model, pool)
                    .run(all, ContextReviewEngineTest.CRITERIA, null);
            assertThat(parallel.accepted()).extracting(a -> a.candidateId() + "/" + a.criterion().id() + "/" + a.core().get(0).id())
                    .containsExactlyElementsOf(sequential.accepted().stream()
                            .map(a -> a.candidateId() + "/" + a.criterion().id() + "/" + a.core().get(0).id()).toList());
            assertThat(parallel.trace()).isEqualTo(sequential.trace());
            assertThat(parallel.modelCalls()).isEqualTo(sequential.modelCalls());
            assertThat(parallel.accepted()).hasSize(10);
            assertThat(model.maxInFlight.get()).isGreaterThan(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void cancellationInAWorkerStopsTheRun() {
        var pool = Executors.newFixedThreadPool(4);
        AtomicInteger checks = new AtomicInteger();
        try {
            var engine = new ContextReviewEngine(ContextProperties.defaults(), new StatelessModel(5), pool);
            assertThatThrownBy(() -> engine.run(lines(100), ContextReviewEngineTest.CRITERIA, () -> {
                if (checks.incrementAndGet() > 2) throw new CancellationException("stopped");
            })).isInstanceOf(CancellationException.class);
        } finally {
            pool.shutdownNow();
        }
    }
}
