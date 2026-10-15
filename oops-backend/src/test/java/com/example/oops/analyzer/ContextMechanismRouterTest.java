package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.AnalyzerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ContextMechanismRouter.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock provider only: routing wiring and fallback, not measured classification accuracy. */
class ContextMechanismRouterTest {
    final OpenAiClient client = mock(OpenAiClient.class);
    final ContextExampleSelectionTest f = new ContextExampleSelectionTest();
    final ReviewGuidelineLibrary units = f.library(new ContextUnitRoutingTest().units(), 2, 3000, "");
    ContextMechanismRouter router(boolean enabled, int max) {
        return router(enabled,max,JSON.writeValueAsString(Map.of("schemaVersion","context-routing-keys-1", "keys",List.of(
                new Key("graphic-body-metaphor","신체 비유 검색"),new Key("local-life-devaluation","지역 선택지 검색")),
                "classificationImplemented",false)));
    }
    ContextMechanismRouter router(boolean enabled, int max, String registry) {
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("classpath:routes.json")).thenReturn(new ByteArrayResource(registry.getBytes(StandardCharsets.UTF_8)));
        var router = new ContextMechanismRouter(client,resources,enabled,"classpath:routes.json",max);
        router.load(); return router;
    }
    Response response(String key, String... ids) { return new Response(List.of(new Route(key,List.of(ids)))); }
    @Test void classifiesBatchAndLimitsCandidateKeysToItsEvidenceIds() {
        var router = router(true,12);
        when(client.completeRoutingAsJson(eq(PROMPT),anyString(),eq(Response.class)))
                .thenReturn(Optional.of(response("graphic-body-metaphor","current-1")));
        var result = router.session().route(units,f.query("완전히 다른 표현"),"discovery-1");
        assertThat(result.keys()).containsExactly("graphic-body-metaphor");
        assertThat(result.keysFor(Set.of("unrelated"))).isEmpty();
        assertThat(result.keysFor(Set.of("current-1"))).isEqualTo(result.keys());
        assertThat(result.trace().state()).isEqualTo("ROUTED");
        assertThat(result.trace().inputSha256()).hasSize(64);
        assertThat(JSON.writeValueAsString(result.trace())).doesNotContain("완전히 다른 표현");
        assertThat(units.select(com.example.oops.domain.TimelineEventType.SPEECH,f.query("완전히 다른 표현"),"q",List.of(),result.keys())
                .trace().exampleIds()).containsExactly("source-0");
    }
    @Test void skipsDisabledLegacyExcludedAndZeroBudgetWithoutProviderCall() {
        assertThat(router(false,12).session().route(units,f.query("text"),"q").trace().state()).isEqualTo("DISABLED");
        assertThat(router(true,12).session().route(f.library(f.archive(2),2,3000,""),f.query("text"),"q")
                .trace().state()).isEqualTo("REQUIRES_V6");
        var excluded = f.library(new ContextUnitRoutingTest().units(),2,3000,"family-0,family-1");
        assertThat(router(true,12).session().route(excluded,f.query("text"),"q").trace().state()).isEqualTo("NO_ROUTABLE_KEYS");
        assertThat(router(true,0).session().route(units,f.query("text"),"q").trace().state()).isEqualTo("CALL_BUDGET_EXHAUSTED");
        verifyNoInteractions(client);
    }
    @Test void invalidEmptyAndFailedResponsesFallBackWithoutRepairOrInventedKeys() {
        List<Response> invalid = Arrays.asList(null,new Response(null), response("unknown","current-1"),
                response("graphic-body-metaphor","unknown-id"), response("graphic-body-metaphor"),
                response("graphic-body-metaphor","current-1","current-1"),
                new Response(Arrays.asList((Route)null)),
                new Response(List.of(new Route("graphic-body-metaphor",List.of("current-1")),
                        new Route("graphic-body-metaphor",List.of("current-1")))));
        var session = router(true,12).session();
        for (var response : invalid) {
            when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class))).thenReturn(Optional.ofNullable(response));
            var result = session.route(units,f.query("text"),"q");
            assertThat(result.keys()).isEmpty();
            assertThat(result.trace().state()).isIn("NO_RESPONSE","INVALID_RESPONSE");
        }
        when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class))).thenReturn(Optional.of(new Response(List.of())));
        assertThat(session.route(units,f.query("text"),"q").trace().state()).isEqualTo("EMPTY");
        when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class))).thenThrow(new IllegalStateException("private"));
        var failed = session.route(units,f.query("text"),"q");
        assertThat(failed.trace().state()).isEqualTo("REQUEST_FAILED");
        assertThat(JSON.writeValueAsString(failed.trace())).doesNotContain("private");
    }
    @Test void videoBudgetIsLocalAndNoExtraCallOccursForSkippedBatch() {
        var router = router(true,1); var session = router.session();
        when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class))).thenReturn(Optional.empty());
        assertThat(session.route(units,f.query("text"),"q1").trace().attempted()).isTrue();
        assertThat(session.route(units,f.query("text"),"q2").trace().state()).isEqualTo("CALL_BUDGET_EXHAUSTED");
        assertThat(router.session().route(units,f.query("text"),"q3").trace().attempted()).isTrue();
        verify(client,times(2)).completeRoutingAsJson(anyString(),anyString(),eq(Response.class));
    }
    @Test void malformedRegistryAndOversizedInputAndCancellationDoNotSendText() {
        for (String registry : List.of("{}","bad JSON",JSON.writeValueAsString(Map.of("schemaVersion","context-routing-keys-1",
                "keys",List.of(new Key("graphic-body-metaphor","x"),new Key("graphic-body-metaphor","y"))))))
            assertThat(router(true,12,registry).session().route(units,f.query("text"),"q").trace().state())
                    .isIn("INVALID_REGISTRY","REGISTRY_UNAVAILABLE_OR_INVALID");
        var session = router(true,12).session();
        assertThat(session.route(units,List.of(),"q").trace().state()).isEqualTo("INPUT_LIMIT");
        assertThat(session.route(units,f.query("가".repeat(20_001)),"q").trace().state()).isEqualTo("INPUT_LIMIT");
        Thread.currentThread().interrupt();
        try { assertThat(session.route(units,f.query("text"),"q").trace().state()).isEqualTo("INTERRUPTED"); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(client);
    }
    @Test void productionAnalyzerWiringRoutesOnceThenFreezesReferenceThroughRepairWithoutForcingReview() {
        var f = new CandidateReviewEngineTest();
        var context = f.context("그 집은 조용하다", "다른 대화");
        f.discovery(List.of("stt-index-0","stt-index-1"),f.proposal("stt-index-0","그 집"));
        var good = f.assessment("stt-index-0","PASS","그 집");
        when(f.client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class)))
                .thenReturn(Optional.of(response("graphic-body-metaphor","stt-index-0")));
        when(f.client.completeAsJson(anyString(),anyString(),eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1",new CandidateReferenceRepairTest().badId(good))))),
                        Optional.of(new VerificationResult(List.of(new Verification("candidate-1",good)))));
        // Construct the same Spring-wired analyzer path, not a router-only prototype.
        var resources = mock(ResourceLoader.class);
        when(resources.getResource("classpath:routes.json")).thenReturn(new ByteArrayResource(JSON.writeValueAsString(
                Map.of("schemaVersion","context-routing-keys-1","keys",List.of(new Key("graphic-body-metaphor","신체 비유 검색"))))
                .getBytes(StandardCharsets.UTF_8)));
        var router = new ContextMechanismRouter(f.client,resources,true,"classpath:routes.json",12); router.load();
        var library = spy(units);
        var analyzer = new SpeechReviewAnalyzer(f.client,true,true,24);
        analyzer.setGuidelineLibrary(library); analyzer.setMechanismRouter(router);
        assertThat(analyzer.analyze(context)).isEmpty();
        verify(f.client).completeRoutingAsJson(anyString(),anyString(),eq(Response.class));
        verify(library).select(any(),anyCollection(),eq("unit-candidate-1"),anyCollection(),eq(Set.of("graphic-body-metaphor")));
        var systems = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client,times(2)).completeAsJson(systems.capture(),anyString(),eq(VerificationResult.class));
        String first = systems.getAllValues().get(0), second = systems.getAllValues().get(1);
        String marker = "# 범용 맥락 참고 기준";
        // Repair prepends its own contract but must retain the exact frozen reference suffix.
        assertThat(first).contains(marker,"합성 대조이며 실제 관측 아님");
        assertThat(second).contains(first.substring(first.indexOf(marker)));
        assertThat(analyzer.consumeReviewResult(context)).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(AnalyzerStatus.SUCCESS);
            assertThat(r.diagnostics().candidatePipeline().mechanismRouting()).singleElement()
                    .extracting(Trace::state).isEqualTo("ROUTED");
        });
    }
    @Test void unrelatedCandidateDoesNotInheritBatchRoutingKey() {
        var f = new CandidateReviewEngineTest(); var library = spy(units);
        f.discovery(List.of("stt-index-0","stt-index-1"),f.proposal("stt-index-1","그 집"));
        f.verification(new Verification("candidate-1",f.assessment("stt-index-1","PASS","그 집")));
        when(f.client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class)))
                .thenReturn(Optional.of(response("graphic-body-metaphor","stt-index-0")));
        // Use this test's registry with the actual engine's client.
        var router = router(true,12);
        when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class)))
                .thenReturn(Optional.of(response("graphic-body-metaphor","stt-index-0")));
        var result = run(f.client,f.context("다른 화제","그 집은 조용하다"),24,null,null,library,null,0,false,router);
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.findings()).isEmpty();
        verify(library).select(any(),anyCollection(),eq("unit-candidate-1"),anyCollection());
        verify(library,never()).select(any(),anyCollection(),eq("unit-candidate-1"),anyCollection(),
                argThat(keys -> !keys.isEmpty()));
        assertThat(result.diagnostics().candidatePipeline().mechanismRouting()).singleElement()
                .extracting(Trace::state).isEqualTo("ROUTED");
    }
    @Test void failedRoutingAndDisabledRoutingPreserveExactCandidateSnapshotAndDecision() {
        var f = new CandidateReviewEngineTest();
        var context = f.context("그 집은 조용하다");
        f.discovery(List.of("stt-index-0"),f.proposal("stt-index-0","그 집"));
        f.verification(new Verification("candidate-1",f.assessment("stt-index-0","PASS","그 집")));
        var baseline = run(f.client,context,24,null,null,units,null,0,false);
        when(client.completeRoutingAsJson(anyString(),anyString(),eq(Response.class))).thenThrow(new IllegalStateException());
        for (boolean enabled : List.of(false,true)) {
            var result = run(f.client,context,24,null,null,units,null,0,false,router(enabled,12));
            assertThat(result.status()).isEqualTo(baseline.status());
            assertThat(result.findings()).isEmpty();
            assertThat(result.diagnostics().candidatePipeline().contextSelections())
                    .isEqualTo(baseline.diagnostics().candidatePipeline().contextSelections());
            assertThat(result.diagnostics().candidatePipeline().candidates())
                    .isEqualTo(baseline.diagnostics().candidatePipeline().candidates());
        }
        verify(client).completeRoutingAsJson(anyString(),anyString(),eq(Response.class));
    }
    @Test void longVideoBudgetDoesNotStopDiscoveryOnRemainingBatches() {
        var f = new CandidateReviewEngineTest();
        String[] lines = java.util.stream.IntStream.range(0,45).mapToObj(i -> "다른 이야기 " + i).toArray(String[]::new);
        var context = f.context(lines);
        int batches = TextReviewBatchPlanner.plan(context.reviewInput(),com.example.oops.domain.TimelineEventType.SPEECH,3).size();
        assertThat(batches).isGreaterThan(1);
        when(f.client.completeAsJson(anyString(),anyString(),eq(Discovery.class))).thenAnswer(inv -> {
            var json = JSON.readTree((String)inv.getArgument(1));
            List<String> ids = new ArrayList<>(); json.path("primaryIds").forEach(id -> ids.add(id.asText()));
            return Optional.of(new Discovery(ids,List.of(),List.of(),false));
        });
        var result = run(f.client,context,24,null,null,units,null,0,false,router(true,1));
        var diagnostics = result.diagnostics().candidatePipeline();
        assertThat(diagnostics.discoveryCalls()).isEqualTo(batches);
        assertThat(diagnostics.exploredSegments()).isEqualTo(45);
        assertThat(diagnostics.mechanismRouting()).hasSize(batches);
        assertThat(diagnostics.mechanismRouting().stream().filter(Trace::attempted).count()).isOne();
        assertThat(diagnostics.mechanismRouting().stream().skip(1).map(Trace::state))
                .containsOnly("CALL_BUDGET_EXHAUSTED");
        verify(client).completeRoutingAsJson(anyString(),anyString(),eq(Response.class));
    }
}
