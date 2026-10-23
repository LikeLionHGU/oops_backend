package com.example.oops.analyzer;

import com.example.oops.config.SpeechReviewProperties;
import org.junit.jupiter.api.Test;
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
            assertThat(p).isEqualTo(new SpeechReviewProperties(true, true, 24, 0,
                    false, false, false, false, "acts-v1", false));
        });
    }

    @Test void existingKeysBindWithoutRenaming() {
        runner.withPropertyValues("oops.analysis.dialogue-review-enabled=false",
                "oops.analysis.candidate-review-enabled=false",
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
                    new SpeechReviewProperties(false, false, 12, 3, true, true, true,
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

    @Test void springUsesOnlyTypedConstructorAndPreservesEverySetting() {
        runner.withBean(com.example.oops.client.OpenAiClient.class,
                        () -> org.mockito.Mockito.mock(com.example.oops.client.OpenAiClient.class))
                .withBean(VisualContextReviewer.class, () -> org.mockito.Mockito.mock(VisualContextReviewer.class))
                .withBean(ReviewCaseLibrary.class, () -> org.mockito.Mockito.mock(ReviewCaseLibrary.class))
                .withBean(ReviewGuidelineLibrary.class, () -> org.mockito.Mockito.mock(ReviewGuidelineLibrary.class))
                .withBean(ContextMechanismRouter.class, () -> org.mockito.Mockito.mock(ContextMechanismRouter.class))
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
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "mechanismRouter"))
                            .isSameAs(context.getBean(ContextMechanismRouter.class));
                    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(analyzer, "requestTraces"))
                            .isSameAs(context.getBean(com.example.oops.service.ReviewRequestTraceStore.class));
                });
    }
}
