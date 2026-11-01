package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;

/** Local deterministic fallback regression; no model call or accuracy assertion. */
class StructuredFallbackRegressionTest {
    @Test void allFixedInputsMatchExplicitLexicalModeAfterCatalogFailure() throws Exception {
        Path runtime=Path.of("../datasets/controversy/guidelines/runtime.json"),
             suite=Path.of("../datasets/controversy/evaluation/active-context-regression-20261018/inputs.json");
        Assumptions.assumeTrue(Files.isRegularFile(runtime)&&Files.isRegularFile(suite));
        byte[] bytes=Files.readAllBytes(runtime);var rows=JSON.readTree(Files.readAllBytes(suite));
        assertThat(rows.size()).isEqualTo(12);
        var resources=mock(ResourceLoader.class);
        when(resources.getResource("classpath:test.json")).thenReturn(new ByteArrayResource(bytes));
        var disabled=new ReviewGuidelineLibrary(resources,true,"classpath:test.json",6000,3,3000,"",false,true);
        disabled.load();
        try(var catalog=mockStatic(StructuredContextSearch.class)) {
            catalog.when(StructuredContextSearch::load).thenThrow(new IllegalStateException("synthetic outage"));
            var fallback=new ReviewGuidelineLibrary(resources,true,"classpath:test.json",6000,3,3000,"",true,true);
            fallback.load();assertThat(fallback.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
            int nonemptySelections=0;
            for(var row:rows) {
                List<ReviewInput.Segment> raw=new ArrayList<>();
                for(var seg:row.path("raw"))raw.add(new ReviewInput.Segment(seg.path("id").asString(),TimelineEventType.SPEECH,
                        seg.path("startMs").asLong(),seg.path("endMs").asLong(),seg.path("text").asString(),null));
                String anchor=row.path("anchorId").asString();var focus=raw.stream().filter(s->s.id().equals(anchor)).toList();
                assertThat(focus).hasSize(1);
                var a=disabled.select(TimelineEventType.SPEECH,raw,"same",focus);
                var b=fallback.select(TimelineEventType.SPEECH,raw,"same",focus);
                if(!a.trace().exampleIds().isEmpty()) nonemptySelections++;
                assertThat(b.prompt()).isEqualTo(a.prompt());
                assertThat(b.trace().exampleIds()).isEqualTo(a.trace().exampleIds());
            }
            assertThat(nonemptySelections).as("At least one fixed input must exercise lexical example retrieval").isPositive();
        }
    }
}
