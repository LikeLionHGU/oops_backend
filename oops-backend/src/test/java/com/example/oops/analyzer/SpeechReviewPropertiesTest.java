package com.example.oops.analyzer;

import com.example.oops.config.SpeechReviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechReviewPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpeechReviewProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test void defaultsPreserveProductionContract() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(SpeechReviewProperties.class);
            assertThat(p).isEqualTo(new SpeechReviewProperties(true, 24, 0,
                    false, false, false, false, "acts-v1", false));
        });
    }

    @Test void existingKeysBindWithoutRenaming() {
        runner.withPropertyValues("oops.analysis.candidate-review-enabled=true",
                "oops.analysis.candidate-review-max-candidates=12",
                "oops.analysis.candidate-review-evidence-limit=3",
                "oops.analysis.candidate-source-relations-enabled=true",
                "oops.analysis.final-review-basis-enabled=true",
                "oops.analysis.situation-first-verification-enabled=true",
                "oops.analysis.source-understanding-enabled=true",
                "oops.analysis.source-understanding-contract=relations-v3",
                "oops.analysis.rule-candidate-union-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SpeechReviewProperties.class)).isEqualTo(
                    new SpeechReviewProperties(true, 12, 3, true, true, true,
                            true, "relations-v3", true));
        });
    }

    @Test void invalidBudgetsFailAtBinding() {
        for (String property : new String[]{"candidate-review-max-candidates=0",
                "candidate-review-max-candidates=201", "candidate-review-evidence-limit=-1"}) {
            runner.withPropertyValues("oops.analysis." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void retiredSpeechModeFailsAtStartupInsteadOfSilentlySwitchingAlgorithms() {
        runner.withPropertyValues("oops.analysis.candidate-review-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "candidate-review-enabled=false is no longer supported; legacy speech review was removed");
                });
    }

    @Test void springUsesOnlyTypedConstructorAndPreservesEverySetting() {
        runner.withBean(com.example.oops.client.OpenAiClient.class,
                        () -> org.mockito.Mockito.mock(com.example.oops.client.OpenAiClient.class))
                .withBean(VisualContextReviewer.class, () -> org.mockito.Mockito.mock(VisualContextReviewer.class))
                .withBean(ReviewGuidelineLibrary.class, () -> org.mockito.Mockito.mock(ReviewGuidelineLibrary.class))
                .withBean(com.example.oops.service.ReviewRequestTraceStore.class,
                        () -> org.mockito.Mockito.mock(com.example.oops.service.ReviewRequestTraceStore.class))
                .withBean(SpeechReviewAnalyzer.class)
                .withPropertyValues("oops.analysis.candidate-review-max-candidates=12",
                        "oops.analysis.candidate-review-evidence-limit=3",
                        "oops.analysis.source-understanding-contract=relations-v3",
                        "oops.analysis.source-understanding-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var analyzer = context.getBean(SpeechReviewAnalyzer.class);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "maxCandidates")).isEqualTo(12);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "evidenceLimit")).isEqualTo(3);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "sourceUnderstandingEnabled")).isEqualTo(true);
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "sourceUnderstandingVersion"))
                            .isEqualTo(SourceUnderstandingContract.Version.config("relations-v3"));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "guidelineLibrary"))
                            .isSameAs(context.getBean(ReviewGuidelineLibrary.class));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "requestTraces"))
                            .isSameAs(context.getBean(com.example.oops.service.ReviewRequestTraceStore.class));
                });
    }

    static java.util.stream.IntStream featureMasks() {
        return java.util.stream.IntStream.range(0, 32);
    }

    @Test void incompatibleTestOverridesFailBeforeOpeningTraceOrCallingServices() {
        var client = org.mockito.Mockito.mock(com.example.oops.client.OpenAiClient.class);
        var traces = org.mockito.Mockito.mock(com.example.oops.service.ReviewRequestTraceStore.class);
        var context = org.mockito.Mockito.mock(AnalysisContext.class);
        var analyzer = new SpeechReviewAnalyzer(client);
        analyzer.setRequestTraces(traces);
        analyzer.setRuleUnionEnabled(true);
        analyzer.setSourceUnderstandingEnabled(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> analyzer.analyze(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rule union currently requires the baseline verification contract");
        org.mockito.Mockito.verifyNoInteractions(client, traces, context);
    }

    /** Configuration compatibility, not model quality: all five independent flag combinations. */
    @ParameterizedTest(name = "experiment flag mask {0}")
    @MethodSource("featureMasks")
    void unsupportedFeatureCombinationsFailBeforeAcceptingAnalysisJobs(int mask) {
        var client = org.mockito.Mockito.mock(com.example.oops.client.OpenAiClient.class);
        var visual = org.mockito.Mockito.mock(VisualContextReviewer.class);
        var library = org.mockito.Mockito.mock(ReviewGuidelineLibrary.class);
        var traces = org.mockito.Mockito.mock(com.example.oops.service.ReviewRequestTraceStore.class);
        boolean union = (mask & 16) != 0;
        boolean otherFeatures = (mask & 15) != 0;
        runner.withBean(com.example.oops.client.OpenAiClient.class, () -> client)
                .withBean(VisualContextReviewer.class, () -> visual)
                .withBean(ReviewGuidelineLibrary.class, () -> library)
                .withBean(com.example.oops.service.ReviewRequestTraceStore.class, () -> traces)
                .withBean(SpeechReviewAnalyzer.class)
                .withPropertyValues("oops.analysis.candidate-source-relations-enabled=" + ((mask & 1) != 0),
                        "oops.analysis.final-review-basis-enabled=" + ((mask & 2) != 0),
                        "oops.analysis.situation-first-verification-enabled=" + ((mask & 4) != 0),
                        "oops.analysis.source-understanding-enabled=" + ((mask & 8) != 0),
                        "oops.analysis.rule-candidate-union-enabled=" + union)
                .run(context -> {
                    if (union && otherFeatures) {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseMessage(
                                "Rule union currently requires the baseline verification contract");
                    } else {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(SpeechReviewAnalyzer.class)).isNotNull();
                    }
                    // Spring invokes the mocked @PostConstruct hook; no analysis/API call is allowed.
                    assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations())
                            .allSatisfy(invocation -> assertThat(invocation.getMethod().getName())
                                    .isEqualTo("logAccount"));
                    assertThat(org.mockito.Mockito.mockingDetails(library).getInvocations())
                            .allSatisfy(invocation -> assertThat(invocation.getMethod().getName())
                                    .isEqualTo("load"));
                    org.mockito.Mockito.verifyNoInteractions(visual, traces);
                });
    }
}
