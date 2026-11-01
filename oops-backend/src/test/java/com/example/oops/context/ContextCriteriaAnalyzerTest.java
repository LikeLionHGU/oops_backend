package com.example.oops.context;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OopsProperties;
import com.example.oops.context.ContextModelOutput.Discovery;
import com.example.oops.context.ContextModelOutput.DiscoveryCandidate;
import com.example.oops.context.ContextModelOutput.Gate;
import com.example.oops.context.ContextModelOutput.Review;
import com.example.oops.context.criteria.CriteriaLoadException;
import com.example.oops.context.criteria.CriteriaSource;
import com.example.oops.context.criteria.JsonCriteriaSourceTest;
import com.example.oops.domain.ContentGenre;
import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.TranscriptSegment;
import com.example.oops.domain.Video;
import com.example.oops.dto.TimelineEventDto;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextCriteriaAnalyzerTest {

    @TempDir Path temp;

    AnalysisContext context() {
        var video = Video.builder().sourceType(SourceType.UPLOAD).filename("talk.mp4").build();
        ReflectionTestUtils.setField(video, "id", 7L);
        List<TranscriptSegment> transcript = new ArrayList<>();
        String[] texts = {"오늘 게스트분 모셨습니다", "근데 몇 살이에요?", "남자친구는 있어요? 어디 살아요?", "아 그건 비밀이에요"};
        for (int i = 0; i < texts.length; i++) {
            var s = new TranscriptSegment(video, i * 4_000L, i * 4_000L + 3_500, texts[i]);
            ReflectionTestUtils.setField(s, "id", 100L + i);
            transcript.add(s);
        }
        return new AnalysisContext(video, ContentGenre.TALK_PODCAST, transcript, List.of());
    }

    @SuppressWarnings("unchecked")
    static void passThroughScope(OpenAiClient client) {
        when(client.withScope(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<Object>) inv.getArgument(1)).get());
    }

    StorageService storage() {
        return new StorageService(new OopsProperties(new OopsProperties.Storage(temp.toString(), 0, 0), null, null));
    }

    @Test void acceptedReviewBecomesACriterionCardAndTraceIsWritten() throws Exception {
        var client = mock(OpenAiClient.class);
        when(client.isEnabled()).thenReturn(true);
        passThroughScope(client);
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.of(
                new Discovery(List.of(), List.of(new DiscoveryCandidate("G02-C01", List.of("stt-102"), "연애 여부 질문")))));
        when(client.completeAsJson(anyString(), anyString(), eq(Gate.class))).thenReturn(Optional.of(
                new Gate(List.of(new ContextModelOutput.GateResult("c1", "PROCEED", "질문과 대상이 확인됨",
                        List.of(new ContextModelOutput.Evidence("stt-102", "남자친구는 있어요?")),
                        List.of("질문 방식 검토"), List.of("MINOR_TARGET"), List.of(), false)))));
        when(client.completeAsJson(anyString(), anyString(), eq(Review.class))).thenReturn(Optional.of(
                new Review(List.of(new ContextModelOutput.ReviewResult("c1", "REVIEW", "처음 만난 게스트에게 연애 여부를 물음",
                        "나이는 판단하지 않음", List.of(new ContextModelOutput.Evidence("stt-102", "남자친구는 있어요?")),
                        List.of(), List.of())))));

        var analyzer = new ContextCriteriaAnalyzer(JsonCriteriaSourceTest.real(), client, ContextProperties.defaults(), storage());
        var findings = analyzer.analyze(context());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.getCategory()).isEqualTo(RiskCategory.CONTEXT_CRITERION);
            assertThat(f.getCriterionId()).isEqualTo("G02-C01");
            assertThat(f.getCriterionTitle()).isEqualTo("성적 사생활을 공개 질문·추정의 소재로 사용");
            assertThat(f.getCriteriaVersion()).contains("ruleset:oops:all:0.4-draft").contains("independent-review/1.0");
            assertThat(f.getStartMs()).isEqualTo(8_000);
            assertThat(f.getReason()).contains("해석 한계: 나이는 판단하지 않음");
            assertThat(f.validatedSupports().get(0).quotes()).extracting(q -> q.quote()).containsExactly("남자친구는 있어요?");
            var dto = TimelineEventDto.from(f, null, null, null);
            assertThat(dto.title()).isEqualTo("성적 사생활을 공개 질문·추정의 소재로 사용");
            assertThat(dto.criterionId()).isEqualTo("G02-C01");
            assertThat(dto.criterionId()).isEqualTo("G02-C01");
        });
        assertThat(analyzer.consumeCoverageNotice(context())).isEmpty();
        try (var files = Files.list(temp.resolve("context-traces/7"))) {
            var trace = Files.readString(files.findFirst().orElseThrow());
            assertThat(trace).contains("\"criteriaVersion\"").contains("MINOR_TARGET").contains("PROCEED");
        }
    }

    @Test void criteriaLoadFailureFailsTheAnalyzerInsteadOfReturningNoFindings() {
        var client = mock(OpenAiClient.class);
        when(client.isEnabled()).thenReturn(true);
        passThroughScope(client);
        CriteriaSource broken = () -> { throw new CriteriaLoadException("Neo4j 연결 실패"); };
        var analyzer = new ContextCriteriaAnalyzer(broken, client, ContextProperties.defaults(), storage());
        assertThatThrownBy(() -> analyzer.analyze(context())).isInstanceOf(CriteriaLoadException.class);
    }

    @Test void failedModelCallsProduceACoverageNotice() {
        var client = mock(OpenAiClient.class);
        when(client.isEnabled()).thenReturn(true);
        passThroughScope(client);
        when(client.completeAsJson(anyString(), anyString(), eq(Discovery.class))).thenReturn(Optional.empty());
        var analyzer = new ContextCriteriaAnalyzer(JsonCriteriaSourceTest.real(), client, ContextProperties.defaults(), storage());
        var ctx = context();
        assertThat(analyzer.analyze(ctx)).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(ctx)).get().asString().contains("일부만").contains("실패");
    }
}
