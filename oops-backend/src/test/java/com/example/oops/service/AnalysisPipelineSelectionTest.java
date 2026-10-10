package com.example.oops.service;

import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Actual packaged configuration, with no AI or news calls. */
class AnalysisPipelineSelectionTest {
    private List<String> configured(String resource) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        var properties = yaml.getObject();
        return IntStream.range(0, 20)
                .mapToObj(i -> properties.getProperty("oops.analysis.enabled-analyzers[" + i + "]"))
                .takeWhile(v -> v != null).toList();
    }

    @Test void defaultConfigurationDoesNotSelectExternalSearchEvenWithBeansPresent() {
        var keys = configured("application.yml");
        assertThat(keys).containsExactly("subtitle", "speech-review", "context-lexicon", "screen-text", "screen-text-review");
        var allKeys = configured("application-external-review.yml");
        var analyzers = allKeys.stream().map(key -> {
            var analyzer = mock(ContentAnalyzer.class);
            when(analyzer.key()).thenReturn(key);
            return analyzer;
        }).toList();
        var pipeline = mock(AnalysisPipeline.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(pipeline, "properties", new OopsProperties(null, new OopsProperties.Analysis(keys), null));
        ReflectionTestUtils.setField(pipeline, "analyzers", analyzers);
        List<ContentAnalyzer> selected = ReflectionTestUtils.invokeMethod(pipeline, "activeAnalyzers");
        assertThat(selected).extracting(ContentAnalyzer::key).containsExactlyElementsOf(keys);
        assertThat(selected).extracting(ContentAnalyzer::key).doesNotContain("entity-check", "context-check");
    }

    @Test void optionalProfilePreservesCoreOrderAndExplicitlyAddsLegacyExternalSearch() {
        assertThat(configured("application-external-review.yml"))
                .containsExactly("subtitle", "speech-review", "context-lexicon", "screen-text", "screen-text-review", "entity-check", "context-check");
    }
}
