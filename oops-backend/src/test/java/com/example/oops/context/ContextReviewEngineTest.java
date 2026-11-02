package com.example.oops.context;

import com.example.oops.context.ContextModelOutput.AdditionalReview;
import com.example.oops.context.ContextModelOutput.Discovery;
import com.example.oops.context.ContextModelOutput.DiscoveryCandidate;
import com.example.oops.context.ContextModelOutput.Evidence;
import com.example.oops.context.ContextModelOutput.Gate;
import com.example.oops.context.ContextModelOutput.GateResult;
import com.example.oops.context.ContextModelOutput.MissingInformation;
import com.example.oops.context.ContextModelOutput.Review;
import com.example.oops.context.ContextModelOutput.ReviewResult;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.context.criteria.CriteriaModel.CriteriaSnapshot;
import com.example.oops.context.criteria.JsonCriteriaSourceTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ContextReviewEngineTest {

    static final CriteriaSnapshot CRITERIA = JsonCriteriaSourceTest.real().load();

    /** 단계별로 미리 정한 응답을 돌려주고, 받은 프롬프트를 기록한다. */
    static final class FakeModel implements ContextReviewEngine.Model {
        final Deque<Function<String, Object>> discovery = new ArrayDeque<>();
        final Deque<Function<String, Object>> gate = new ArrayDeque<>();
        final Deque<Function<String, Object>> review = new ArrayDeque<>();
        final List<String> stages = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        Function<String, Object> defaultDiscovery = u -> new Discovery(List.of(), List.of());

        @Override
        public <T> Optional<T> call(String system, String user, Class<T> type) {
            systems.add(system);
            users.add(user);
            Object out;
            if (system.contains("[단계: 후보 탐색]")) {
                stages.add("DISCOVERY");
                out = (discovery.isEmpty() ? defaultDiscovery : discovery.poll()).apply(user);
            } else if (system.contains("[단계: 독립 점검]")) {
                stages.add("GATE");
                out = gate.poll().apply(user);
            } else {
                stages.add("REVIEW");
                out = review.poll().apply(user);
            }
            return Optional.ofNullable(type.cast(out));
        }
    }

    static List<ContextLine> talk() {
        List<ContextLine> lines = new ArrayList<>();
        String[] texts = {"오늘 게스트분 모셨습니다", "반갑습니다 잘 부탁드려요", "근데 몇 살이에요?",
                "남자친구는 있어요? 어디 살아요?", "아 그건 좀 비밀이에요", "그럼 다음 질문으로 넘어갈게요"};
        for (int i = 0; i < texts.length; i++) {
            lines.add(new ContextLine("stt-" + (i + 1), i * 4_000L, i * 4_000L + 3_500, texts[i]));
        }
        return lines;
    }

    static Discovery g02At(String... ids) {
        return new Discovery(CRITERIA.criteria().stream().map(c -> c.id()).toList(),
                List.of(new DiscoveryCandidate("G02-C01", List.of(ids), "초면 게스트에게 연애 여부를 물음")));
    }

    static GateResult proceed(String id, List<String> stopped) {
        return new GateResult(id, "PROCEED", "질문과 대상이 확인됨",
                List.of(new Evidence("stt-4", "남자친구는 있어요?")), List.of("사적인 질문 방식 검토"), stopped,
                List.of(new MissingInformation("대상 연령", false)), false);
    }

    static ReviewResult reviewOk(String id, String reason, List<String> used) {
        return new ReviewResult(id, "REVIEW", reason, "질문 방식에 대한 검토 이유이며 대상의 나이는 판단하지 않음",
                List.of(new Evidence("stt-4", "남자친구는 있어요?"), new Evidence("stt-3", "몇 살이에요")), used,
                List.of(new AdditionalReview("G02-A01", "INFO_MISSING", "연령 미확인")));
    }

    ContextReviewEngine engine(FakeModel model) {
        return new ContextReviewEngine(ContextProperties.defaults(), model);
    }

    @Test void partialStopIsCarriedToReviewAndAcceptedResultKeepsIt() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(proceed("c1", List.of("MINOR_TARGET")))));
        model.review.add(u -> new Review(List.of(reviewOk("c1", "처음 만난 게스트에게 연애와 거주지를 연달아 물음", List.of()))));

        var result = engine(model).run(talk(), CRITERIA, null);

        assertThat(model.stages).containsExactly("DISCOVERY", "GATE", "REVIEW");
        assertThat(result.accepted()).singleElement().satisfies(a -> {
            assertThat(a.criterion().id()).isEqualTo("G02-C01");
            assertThat(a.stoppedJudgments()).containsExactly("MINOR_TARGET");
            assertThat(a.core()).extracting(ContextLine::id).containsExactly("stt-4");
            assertThat(a.evidence()).extracting(ContextReviewEngine.AcceptedEvidence::role).containsExactly("PRIMARY", "CONTEXT");
        });
        // 상세 검토 요청에 중단 범위가 전달된다
        assertThat(model.users.get(2)).contains("중단된 판단: MINOR_TARGET(미성년자 대상)");
        // 독립 점검 요청에는 추가 기준을 넣지 않는다
        assertThat(model.systems.get(1)).doesNotContain("## 추가 기준").contains("최소 정보:");
        assertThat(model.systems.get(2)).contains("## 추가 기준").contains("G02-A01");
        assertThat(result.partial()).isFalse();
    }

    @Test void reviewThatReusesAStoppedJudgmentIsRejected() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(proceed("c1", List.of("MINOR_TARGET")))));
        model.review.add(u -> new Review(List.of(reviewOk("c1", "질문 방식", List.of("MINOR_TARGET")))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.invalidOutputs()).isEqualTo(1);
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.note()).contains("MINOR_TARGET"));
    }

    @Test void reviewReasonThatMentionsAStoppedJudgmentIsRejected() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(proceed("c1", List.of("MINOR_TARGET")))));
        model.review.add(u -> new Review(List.of(reviewOk("c1", "미성년자 대상 질문으로 보일 수 있음", List.of()))));
        assertThat(engine(model).run(talk(), CRITERIA, null).accepted()).isEmpty();
    }

    @Test void neverAssertCodeIsRejected() {
        var model = new FakeModel();
        model.discovery.add(u -> new Discovery(List.of(),
                List.of(new DiscoveryCandidate("G16-C01", List.of("stt-6"), "기대 표현"))));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "PROCEED", "기대와 조건이 확인됨",
                List.of(new Evidence("stt-6", "다음 질문")), List.of("차이 검토"), List.of(), List.of(), false))));
        model.review.add(u -> new Review(List.of(new ReviewResult("c1", "REVIEW", "기대와 다름", null,
                List.of(new Evidence("stt-6", "다음 질문")), List.of("INTENTIONAL_DECEPTION"), List.of()))));
        assertThat(engine(model).run(talk(), CRITERIA, null).accepted()).isEmpty();
    }

    @Test void fabricatedQuoteInGateStopsBeforeReview() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "PROCEED", "확인됨",
                List.of(new Evidence("stt-4", "몇 살이니 꼬마야")), List.of("질문"), List.of(), List.of(), false))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE");
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.note()).contains("원문에 없는 인용"));
    }

    @Test void outOfCoreDiscoveryIsInvalidAndUnknownStopCodeStopsTheCandidate() {
        var model = new FakeModel();
        model.discovery.add(u -> new Discovery(List.of(), List.of(
                new DiscoveryCandidate("G02-C01", List.of("stt-99"), "없는 구간"),
                new DiscoveryCandidate("G99-C01", List.of("stt-4"), "없는 기준"),
                new DiscoveryCandidate("G02-C01", List.of("stt-4"), "정상"))));
        model.gate.add(u -> new Gate(List.of(proceed("c1", List.of("기준 전체 중단")))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(result.invalidOutputs()).isEqualTo(2);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE");
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> {
            assertThat(t.stage()).isEqualTo("GATE");
            assertThat(t.outcome()).isEqualTo("STOP");
            assertThat(t.note()).contains("기준 전체 중단");
        });
    }

    @Test void discoveryKeepsOnlyCoreLinesWhenSomeAreOutside() {
        var model = new FakeModel();
        model.discovery.add(u -> new Discovery(List.of(), List.of(
                new DiscoveryCandidate("G02-C01", List.of("stt-4", "stt-99"), "일부는 밖"))));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "EXCLUDE", "제외 조건", List.of(), List.of(), List.of(), List.of(), false))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(result.invalidOutputs()).isZero();
        assertThat(result.trace()).anySatisfy(t -> {
            assertThat(t.stage()).isEqualTo("DISCOVERY");
            assertThat(t.coreSegmentIds()).containsExactly("stt-4");
        });
    }

    @Test void expansionHappensOnceAndOnlyForTranscriptInformation() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "STOP", "앞 대화가 더 필요",
                List.of(), List.of(), List.of(), List.of(new MissingInformation("질문의 대상", true)), true))));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "STOP", "여전히 부족",
                List.of(), List.of(), List.of(), List.of(new MissingInformation("질문의 대상", true)), true))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE", "GATE");
        assertThat(model.users.get(2)).contains("확대된 문맥");
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> {
            assertThat(t.stage()).isEqualTo("GATE_EXPANDED");
            assertThat(t.outcome()).isEqualTo("STOP");
            assertThat(t.expanded()).isTrue();
        });
    }

    @Test void informationOutsideTranscriptIsNotExpanded() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "STOP", "나이를 알 수 없음",
                List.of(), List.of(), List.of(), List.of(new MissingInformation("대상 연령", false)), true))));
        engine(model).run(talk(), CRITERIA, null);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE");
    }

    @Test void excludedCandidateNeverReachesReview() {
        var model = new FakeModel();
        model.discovery.add(u -> g02At("stt-4"));
        model.gate.add(u -> new Gate(List.of(new GateResult("c1", "EXCLUDE", "게스트가 먼저 꺼낸 얘기",
                List.of(new Evidence("stt-5", "비밀이에요")), List.of(), List.of(), List.of(), false))));
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE");
        assertThat(result.accepted()).isEmpty();
    }

    @Test void failedCallIsCountedAsFailureNotAsNoProblem() {
        var model = new FakeModel();
        model.discovery.add(u -> null);
        var result = engine(model).run(talk(), CRITERIA, null);
        assertThat(result.failedCalls()).isEqualTo(1);
        assertThat(result.partial()).isTrue();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("FAILED"));
    }

    @Test void sameCriterionCandidatesAreBatchedByFive() {
        List<ContextLine> lines = new ArrayList<>();
        for (int i = 0; i < 7; i++) lines.add(new ContextLine("stt-" + i, i * 2_000L, i * 2_000L + 1_500, "발언 " + i));
        var model = new FakeModel();
        List<DiscoveryCandidate> cands = new ArrayList<>();
        for (int i = 0; i < 7; i++) cands.add(new DiscoveryCandidate("G13-C01", List.of("stt-" + i), "공격 표현"));
        model.discovery.add(u -> new Discovery(List.of(), cands));
        model.gate.add(u -> new Gate(List.of()));
        model.gate.add(u -> new Gate(List.of()));
        var result = engine(model).run(lines, CRITERIA, null);
        assertThat(model.stages).containsExactly("DISCOVERY", "GATE", "GATE");
        assertThat(model.users.get(1)).contains("# 후보 c5").doesNotContain("# 후보 c6");
        assertThat(result.invalidOutputs()).isEqualTo(7);   // 결과 누락은 INVALID
    }

    @Test void cancellationIsCheckedBeforeEachCall() {
        var model = new FakeModel();
        int[] checks = {0};
        engine(model).run(talk(), CRITERIA, () -> checks[0]++);
        assertThat(checks[0]).isEqualTo(model.stages.size());
    }
}
