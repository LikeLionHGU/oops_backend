package com.example.oops.factcheck;

import com.example.oops.context.ContextProperties;
import com.example.oops.context.ContextReviewEngine;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.factcheck.FactCheckPrompts.ExtractedClaim;
import com.example.oops.factcheck.FactCheckPrompts.Extraction;
import com.example.oops.factcheck.FactCheckPrompts.Judgment;
import com.example.oops.factcheck.FactCheckPrompts.SourceEvidence;
import com.example.oops.factcheck.FactCheckPrompts.Supplement;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class FactCheckEngineTest {

    static final class FakeModel implements ContextReviewEngine.Model {
        final Deque<Function<String, Object>> extract = new ArrayDeque<>();
        final Deque<Function<String, Object>> judge = new ArrayDeque<>();
        final List<String> stages = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        final List<String> users = new ArrayList<>();

        @Override
        public <T> Optional<T> call(String system, String user, Class<T> type) {
            systems.add(system);
            users.add(user);
            Object out;
            if (type == Extraction.class) {
                stages.add("EXTRACT");
                out = extract.isEmpty() ? new Extraction(List.of()) : extract.poll().apply(user);
            } else {
                stages.add("JUDGE");
                out = judge.poll().apply(user);
            }
            return Optional.ofNullable(type.cast(out));
        }
    }

    static final class FakeSearch implements Search.Provider {
        final String name;
        final Deque<Function<String, Search.Outcome>> answers = new ArrayDeque<>();
        final List<String> queries = new ArrayList<>();
        boolean enabled = true;

        FakeSearch(String name) {
            this.name = name;
        }

        public String name() { return name; }

        public boolean isEnabled() { return enabled; }

        public Search.Outcome search(String query, int max) {
            queries.add(query);
            return answers.isEmpty() ? Search.Outcome.of(name, query, List.of()) : answers.poll().apply(query);
        }
    }

    static List<ContextLine> lines() {
        return List.of(
                new ContextLine("stt-1", 0L, 3_000L, "그 밴드 데뷔가 2015년이었잖아요"),
                new ContextLine("stt-2", 3_000L, 6_000L, "그때부터 좋아했어요"),
                new ContextLine("stt-3", 6_000L, 9_000L, "그 사람이 그랬다던데"));
    }

    static ExtractedClaim debut() {
        return new ExtractedClaim(List.of("stt-1"), "데뷔가 2015년", "밴드 A", "밴드 A의 데뷔 연도는 2015년이다",
                List.of(), true, "밴드 A 데뷔 연도");
    }

    static Search.Item item(String title, String snippet, String url) {
        return new Search.Item(title, snippet, "2024-01-01", url, null);
    }

    FakeModel model = new FakeModel();
    FakeSearch perplexity = new FakeSearch("perplexity");
    FakeSearch serper = new FakeSearch("serper");

    FactCheckEngine engine() {
        return new FactCheckEngine(FactCheckProperties.defaults(), ContextProperties.defaults(), model, perplexity, serper, Runnable::run);
    }

    @Test void refutedClaimBecomesResultWithOnlyTheSourcesUsed() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(
                item("밴드 A 프로필", "밴드 A는 2016년 데뷔했다", "https://a.example/profile"),
                item("다른 기사", "관련 없는 내용", "https://b.example/x"))));
        model.judge.add(u -> new Judgment("REFUTED", "자료의 데뷔 연도가 다름", "2016년 데뷔",
                List.of(new SourceEvidence("s1", "2016년 데뷔했다")), List.of(), null));

        var result = engine().run(lines(), null);

        assertThat(result.accepted()).singleElement().satisfies(a -> {
            assertThat(a.subject()).isEqualTo("밴드 A");
            assertThat(a.sources()).extracting(s -> s.item().url()).containsExactly("https://a.example/profile");
            assertThat(a.segments()).extracting(ContextLine::id).containsExactly("stt-1");
        });
        assertThat(serper.queries).isEmpty();
        assertThat(model.stages).containsExactly("EXTRACT", "JUDGE");
    }

    @Test void unspecifiedTargetIsNeverSearched() {
        model.extract.add(u -> new Extraction(List.of(new ExtractedClaim(List.of("stt-3"), "그 사람이 그랬다", null,
                "누군가 무언가를 했다", List.of(), false, ""))));
        var result = engine().run(lines(), null);
        assertThat(perplexity.queries).isEmpty();
        assertThat(serper.queries).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("NOT_SPECIFIC"));
    }

    @Test void supplementIsSearchedOnceWithSerperAndSecondRoundCannotAskAgain() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(item("밴드 A 소개", "결성 이야기", "https://a.example/intro"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of("데뷔 연도"),
                new Supplement("공식 데뷔 연도", "데뷔 연도 확인", "밴드 A 공식 데뷔 앨범 발매일")));
        serper.answers.add(q -> Search.Outcome.of("serper", q, List.of(item("밴드 A 1집", "2016년 발매된 데뷔 앨범", "https://c.example/album"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of(),
                new Supplement("x", "y", "또 다른 검색어")));

        var result = engine().run(lines(), null);

        assertThat(serper.queries).containsExactly("밴드 A 공식 데뷔 앨범 발매일");
        assertThat(model.stages).containsExactly("EXTRACT", "JUDGE", "JUDGE");
        assertThat(model.systems.get(2)).contains("supplement 는 반드시 null");
        assertThat(model.users.get(2)).contains("[s1]").contains("[s2]");
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("INSUFFICIENT"));
    }

    @Test void supplementWithTheSameQueryIsNotSearched() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(item("밴드 A", "결성", "https://a.example"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of(),
                new Supplement("연도", "확인", "밴드 A 데뷔 연도")));
        engine().run(lines(), null);
        assertThat(serper.queries).isEmpty();
    }

    @Test void primaryOutageUsesSerperAsTheOnlySecondaryCall() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.failed("perplexity", q, "장애"));
        serper.answers.add(q -> Search.Outcome.of("serper", q, List.of(item("밴드 A", "결성", "https://a.example"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of(),
                new Supplement("연도", "확인", "밴드 A 1집 발매")));
        var result = engine().run(lines(), null);
        assertThat(serper.queries).containsExactly("밴드 A 데뷔 연도");   // 보조 검색으로 더 늘지 않는다
        assertThat(result.failedSearches()).isOne();
        assertThat(result.partial()).isTrue();
    }

    @Test void fabricatedSourceQuoteIsInvalidAndNotShown() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(item("밴드 A", "2016년 데뷔", "https://a.example"))));
        model.judge.add(u -> new Judgment("REFUTED", "다름", "2016년", List.of(new SourceEvidence("s1", "2017년 데뷔")), List.of(), null));
        var result = engine().run(lines(), null);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.invalidOutputs()).isOne();
    }

    @Test void noResultsSkipsJudgmentAndBothOutagesAreSearchFailures() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        engine().run(lines(), null);
        assertThat(model.stages).containsExactly("EXTRACT");

        model = new FakeModel();
        perplexity = new FakeSearch("perplexity");
        serper = new FakeSearch("serper");
        perplexity.enabled = false;
        serper.answers.add(q -> Search.Outcome.failed("serper", q, "장애"));
        model.extract.add(u -> new Extraction(List.of(debut())));
        var result = engine().run(lines(), null);
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("SEARCH_FAILED"));
        assertThat(result.accepted()).isEmpty();
    }

    @Test void duplicateUrlsAndSameOriginalTitlesAreMerged() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(
                item("밴드 A 데뷔 10주년 기념 인터뷰", "본문", "https://a.example/news?id=1&utm_source=x"),
                item("밴드 A 데뷔 10주년 기념 인터뷰", "본문", "https://mirror.example/copy"),
                item("다른 글", "본문", "https://a.example/news?id=1"),
                item("세 번째", "본문", "https://a.example/news?id=2"))));
        model.judge.add(u -> new Judgment("SUPPORTED", null, null, List.of(new SourceEvidence("s1", "본문")), List.of(), null));
        engine().run(lines(), null);
        assertThat(model.users.get(1)).contains("[s1]").contains("[s2] 세 번째").doesNotContain("[s3]");
    }

    @Test void extractionQuoteMustExistInTheSegment() {
        model.extract.add(u -> new Extraction(List.of(new ExtractedClaim(List.of("stt-1"), "데뷔가 2014년", "밴드 A",
                "밴드 A 데뷔 2014년", List.of(), true, "밴드 A 데뷔"))));
        var result = engine().run(lines(), null);
        assertThat(result.invalidOutputs()).isOne();
        assertThat(perplexity.queries).isEmpty();
    }

    // ── 470번 영상 시험 후 추가 ──

    static List<ContextLine> quiz() {
        return List.of(
                new ContextLine("stt-1", 0L, 2_000L, "터키의 수도는?"),
                new ContextLine("stt-2", 2_000L, 4_000L, "이스탄불이었습니다."),
                new ContextLine("stt-3", 4_000L, 6_000L, "나 이거 알았는데."),
                new ContextLine("stt-4", 6_000L, 8_000L, "땡 정답은 앙카라입니다."));
    }

    static ExtractedClaim capital() {
        return new ExtractedClaim(List.of("stt-2"), "이스탄불이었습니다", "터키", "터키의 수도는 이스탄불이다", List.of(), true, "터키 수도");
    }

    static Search.Outcome ankara(String q) {
        return Search.Outcome.of("perplexity", q, List.of(item("터키 국가 정보", "터키의 수도는 앙카라이다", "https://mofa.example/tr")));
    }

    @Test void refutedButNotAssertedIsDropped() {
        model.extract.add(u -> new Extraction(List.of(capital())));
        perplexity.answers.add(FactCheckEngineTest::ankara);
        model.judge.add(u -> new Judgment("REFUTED", "다름", "앙카라", List.of(new SourceEvidence("s1", "터키의 수도는 앙카라")),
                List.of(), null, false, false));
        var result = engine().run(quiz(), null);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("NOT_ASSERTED"));
    }

    @Test void correctedInVideoIsDroppedAndJudgeSeesFollowingLines() {
        model.extract.add(u -> new Extraction(List.of(capital())));
        perplexity.answers.add(FactCheckEngineTest::ankara);
        model.judge.add(u -> new Judgment("REFUTED", "다름", "앙카라", List.of(new SourceEvidence("s1", "터키의 수도는 앙카라")),
                List.of(), null, true, true));
        var result = engine().run(quiz(), null);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.outcome()).isEqualTo("CORRECTED_IN_VIDEO"));
        assertThat(model.users.get(1)).contains("## 뒤 문맥").contains("[stt-4] 땡 정답은 앙카라입니다.")
                .contains("## 앞 문맥").contains("[stt-1] 터키의 수도는?");
    }

    @Test void genericSubjectsAndQuestionsAreNeverSearched() {
        model.extract.add(u -> new Extraction(List.of(
                new ExtractedClaim(List.of("stt-1"), "그 밴드 데뷔가", "화자의 남편", "화자의 남편은 유도를 했다", List.of(), true, "화자 남편 유도"),
                new ExtractedClaim(List.of("stt-1"), "그 밴드 데뷔가", "밴드 A", "밴드 A 의 데뷔 연도는 무엇인가", List.of(), true, "밴드 A 데뷔"))));
        var result = engine().run(lines(), null);
        assertThat(perplexity.queries).isEmpty();
        assertThat(result.trace()).extracting(FactCheckEngine.TraceEntry::outcome).containsExactly("NOT_SPECIFIC", "NOT_ASSERTION");
    }

    @Test void evidenceThatDoesNotMentionTheSubjectIsNotAttached() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(
                item("시드니 식당 안내", "2016년 문을 연 식당", "https://sydney.example/food"))));
        model.judge.add(u -> new Judgment("REFUTED", "다름", "2016년", List.of(new SourceEvidence("s1", "2016년 문을 연")),
                List.of(), null));
        var result = engine().run(lines(), null);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.trace()).anySatisfy(t -> assertThat(t.note()).contains("대상이 나오지 않음"));
    }

    @Test void searchesAreCountedPerProvider() {
        model.extract.add(u -> new Extraction(List.of(debut())));
        perplexity.answers.add(q -> Search.Outcome.of("perplexity", q, List.of(item("밴드 A 소개", "결성", "https://a.example/intro"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of(),
                new Supplement("연도", "확인", "밴드 A 1집 발매일")));
        serper.answers.add(q -> Search.Outcome.of("serper", q, List.of(item("밴드 A 1집", "2016년", "https://c.example"))));
        model.judge.add(u -> new Judgment("INSUFFICIENT", null, null, List.of(), List.of(), null));
        var result = engine().run(lines(), null);
        assertThat(result.searchesByProvider()).containsEntry("perplexity", 1).containsEntry("serper", 1);
    }
}
