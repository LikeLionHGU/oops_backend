package com.example.oops.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional private-data wiring test; no provider calls or model accuracy claims. */
class LocalDevelopmentBoundaryRetrievalTest {
    @Test void approvedDevelopmentBoundariesLoadAndAreRetrievedFromTheirStoredExcerpt() throws Exception {
        Path root = Path.of("../datasets/controversy");
        Path archive = root.resolve("guidelines/runtime.json");
        for (String key : new String[]{"victim-quotation", "audience-devaluation"}) {
            assumeTrue(Files.exists(root.resolve("context-sources/mimiminu-" + key + "-20261022.json")));
        }
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                archive.toAbsolutePath().toUri().toString(), 6000);
        library.load();
        assertThat(library.trace(SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        for (String key : new String[]{"victim-quotation", "audience-devaluation"}) {
            var source = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(
                    root.resolve("context-sources/mimiminu-" + key + "-20261022.json")));
            var raw = new ArrayList<ReviewInput.Segment>();
            for (var row : source.get("segments")) {
                raw.add(new ReviewInput.Segment(row.get("id").asText(), SPEECH,
                        row.get("startMs").asLong(), row.get("endMs").asLong(), row.get("text").asText(), null));
            }
            // No manual routing hints, relation records, or model-generated query.
            var selected = library.select(SPEECH, raw, "local-boundary-" + key);
            assertThat(selected.trace().exampleIds()).contains("mimiminu-dev#" + key);
            assertThat(selected.prompt()).contains("공개 논란 사건이 아닌 사용자 승인 개발 경계");
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
        }
    }
}
