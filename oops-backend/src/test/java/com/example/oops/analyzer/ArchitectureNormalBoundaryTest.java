package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Local casebook retrieval check, never a model decision or an independent accuracy test. */
class ArchitectureNormalBoundaryTest {
    @Test void approvedObservedNormalCasesAreRetrievedWithoutRewritingSttOrChangingBudgets() throws Exception {
        var root = Path.of("../datasets/controversy");
        assumeTrue(Files.exists(root.resolve("context-units/mimiminu-architecture-sketch-critique.json")));
        var runtime = root.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString();
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000, "", true);
        library.load();
        for (var slug : List.of("architecture-sketch-critique", "architecture-helper-terminology")) {
            var unit = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve("context-units/mimiminu-" + slug + ".json")));
            var source = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve(unit.at("/source/path").asText())));
            assertThat(unit.path("developmentDecision").asText()).isEqualTo("PASS");
            assertThat(unit.path("humanVerified").asBoolean()).isFalse();
            var raw = new ArrayList<ReviewInput.Segment>();
            for (var s : source.get("segments")) raw.add(new ReviewInput.Segment(s.path("id").asText(), SPEECH,
                    s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
            var selected = library.select(SPEECH, raw, slug, raw);
            assertThat(selected.trace().exampleIds()).contains(unit.path("id").asText()).hasSizeLessThanOrEqualTo(3);
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            assertThat(selected.prompt()).doesNotContain("approvalRecord", "trainingUseAuthorized");
            if (slug.equals("architecture-sketch-critique")) {
                assertThat(selected.prompt()).contains("잘 그리지 않나?", "움찔", "천사시대");
            }
            var excluded = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000,
                    unit.path("familyId").asText(), true);
            excluded.load();
            assertThat(excluded.select(SPEECH, raw, slug, raw).trace().exampleIds()).doesNotContain(unit.path("id").asText());
            System.out.println("ARCHITECTURE_BOUNDARY " + slug + " examples=" + selected.trace().exampleIds()
                    + " payloadCodePoints=" + selected.trace().payloadCodePoints());
        }
    }
}
