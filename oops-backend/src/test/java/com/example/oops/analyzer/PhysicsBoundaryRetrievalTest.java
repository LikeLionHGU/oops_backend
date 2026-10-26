package com.example.oops.analyzer;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Private development assets are optional; retrieval success is not judgment accuracy. */
@org.junit.jupiter.api.Tag("dataset-backed")
class PhysicsBoundaryRetrievalTest {
    @Test void approvedBoundariesPreserveSourceAndRetrieveWithinExistingBudgets() throws Exception {
        var root = Path.of("../datasets/controversy");
        assumeTrue(Files.exists(root.resolve("context-units/mimiminu-physics-choice-dismissal.json")));
        var runtime = root.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString();
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000, "", true);
        library.load();
        for (var slug : List.of("physics-choice-dismissal", "physics-affinity-banter", "physics-facial-care", "physics-hygiene-boundary")) {
            var unit = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve("context-units/mimiminu-" + slug + ".json")));
            var bytes = Files.readAllBytes(root.resolve(unit.at("/source/path").asText()));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(unit.at("/source/sha256").asText());
            var source = ReviewGuidelineLibrary.JSON.readTree(bytes);
            var origin = root.resolve(source.at("/origin/path").asText());
            var snapshot = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(origin));
            var raw = new ArrayList<ReviewInput.Segment>();
            for (var s : source.get("segments")) {
                int index = Integer.parseInt(s.path("id").asText().replace("stt-index-", ""));
                var saved = snapshot.path("transcript").get(index);
                for (var field : List.of("text", "startMs", "endMs")) assertThat(s.get(field)).isEqualTo(saved.get(field));
                raw.add(new ReviewInput.Segment(s.path("id").asText(), SPEECH,
                        s.path("startMs").asLong(), s.path("endMs").asLong(), s.path("text").asText(), null));
            }
            assertThat(unit.path("developmentDecision").asText()).isEqualTo(slug.contains("choice") ? "REVIEW_REQUIRED" : "PASS");
            var selection = library.select(SPEECH, raw, slug, raw);
            assertThat(selection.trace().exampleIds()).contains(unit.path("id").asText()).hasSizeLessThanOrEqualTo(3);
            assertThat(selection.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            assertThat(selection.prompt()).doesNotContain("approvalRecord", "trainingUseAuthorized");
            var excluded = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000,
                    unit.path("familyId").asText(), true);
            excluded.load();
            assertThat(excluded.select(SPEECH, raw, slug, raw).trace().exampleIds()).doesNotContain(unit.path("id").asText());
            System.out.println("PHYSICS_BOUNDARY " + slug + " examples=" + selection.trace().exampleIds()
                    + " payloadCodePoints=" + selection.trace().payloadCodePoints());
        }
    }
}
