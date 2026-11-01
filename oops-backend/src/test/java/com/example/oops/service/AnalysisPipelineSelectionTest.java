package com.example.oops.service;

import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** redesign-v3: 기본 분석은 맥락 확인(context-review)과 사실대조(fact-check) 두 흐름뿐이다. */
class AnalysisPipelineSelectionTest {
    static List<String> configured(String resource) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        var properties = yaml.getObject();
        return IntStream.range(0, 20)
                .mapToObj(i -> properties.getProperty("oops.analysis.enabled-analyzers[" + i + "]"))
                .takeWhile(v -> v != null).toList();
    }

    @Test void defaultConfigurationRunsOnlyTheTwoRedesignFlowsInConfiguredOrder() {
        var keys = configured("application.yml");
        assertThat(keys).containsExactly("context-review", "fact-check");
        var analyzers = Stream.concat(Stream.of("unknown-old-analyzer"), keys.stream()).map(key -> {
            var analyzer = mock(ContentAnalyzer.class);
            when(analyzer.key()).thenReturn(key);
            return analyzer;
        }).toList();
        var pipeline = mock(AnalysisPipeline.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(pipeline, "properties", new OopsProperties(null, new OopsProperties.Analysis(keys), null));
        ReflectionTestUtils.setField(pipeline, "analyzers", analyzers);
        List<ContentAnalyzer> selected = ReflectionTestUtils.invokeMethod(pipeline, "activeAnalyzers");
        assertThat(selected).extracting(ContentAnalyzer::key).containsExactlyElementsOf(keys);
    }
}
