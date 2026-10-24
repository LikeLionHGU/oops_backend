package com.example.oops.analyzer;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Private source-linked retrieval probes, not model accuracy or independent evaluation. */
class PodcastBoundaryRetrievalTest {
    private String sha(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
    @Test void approvedSixFlowsKeepScriptAndApprovalProvenanceAndRetrieveWithinBudget() throws Exception {
        var root = Path.of("../datasets/controversy");
        assumeTrue(Files.exists(root.resolve("context-units/podcast-social-defect.json")));
        var runtime = root.resolve("guidelines/runtime.json").toAbsolutePath().toUri().toString();
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000, "", true);
        library.load();
        for (var slug : List.of("podcast-introduction-banter", "podcast-practice-devaluation", "podcast-clothing-banter",
                "podcast-boast-response", "podcast-colleague-metaphor", "podcast-social-defect")) {
            var unit = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve("context-units/" + slug + ".json")));
            var bytes = Files.readAllBytes(root.resolve(unit.at("/source/path").asText()));
            assertThat(sha(bytes)).isEqualTo(unit.at("/source/sha256").asText());
            var source = ReviewGuidelineLibrary.JSON.readTree(bytes);
            assertThat(source.path("sourceKind").asText()).isEqualTo("USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED");
            assertThat(source.path("sourceGroupId").asText()).isEqualTo("youtube-Duvb0IftlbI");
            var originalBytes = Files.readAllBytes(root.resolve(source.at("/origin/path").asText()));
            assertThat(sha(originalBytes)).isEqualTo(source.at("/origin/sha256").asText());
            var original = ReviewGuidelineLibrary.JSON.readTree(originalBytes);
            var approvalBytes = Files.readAllBytes(root.resolve(source.at("/developmentReview/approvalRecord/path").asText()));
            assertThat(sha(approvalBytes)).isEqualTo(source.at("/developmentReview/approvalRecord/sha256").asText());
            var approvals = ReviewGuidelineLibrary.JSON.readTree(approvalBytes).path("reviews");
            for (var id : source.at("/developmentReview/approvalRecord/eventIds")) {
                boolean found = false;
                for (var approval : approvals) {
                    boolean matches = id.asText().equals(approval.path("eventId").asText());
                    for (var groupedId : approval.path("eventIds")) matches |= groupedId.asText().equals(id.asText());
                    if (matches) {
                        found = true;
                        assertThat(approval.path("decision").asText()).isEqualTo(unit.path("developmentDecision").asText());
                    }
                }
                assertThat(found).isTrue();
            }
            var raw = new ArrayList<ReviewInput.Segment>();
            for (var s : source.path("segments")) {
                int index = Integer.parseInt(s.path("id").asText().replace("stt-index-", ""));
                var saved = original.path("transcript").get(index);
                for (var field : List.of("text", "startMs", "endMs")) assertThat(s.get(field)).isEqualTo(saved.get(field));
                raw.add(new ReviewInput.Segment(s.path("id").asText(), SPEECH, s.path("startMs").asLong(),
                        s.path("endMs").asLong(), s.path("text").asText(), null));
            }
            var selected = library.select(SPEECH, raw, slug, raw);
            assertThat(selected.trace().exampleIds()).contains(unit.path("id").asText()).hasSizeLessThanOrEqualTo(3);
            assertThat(selected.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            assertThat(selected.prompt()).doesNotContain("approvalRecord", "trainingUseAuthorized");
            var excluded = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true, runtime, 6000, 3, 3000,
                    unit.path("familyId").asText(), true);
            excluded.load();
            assertThat(excluded.select(SPEECH, raw, slug, raw).trace().exampleIds()).doesNotContain(unit.path("id").asText());
            System.out.println("PODCAST_BOUNDARY " + slug + " selected=" + selected.trace().exampleIds()
                    + " payload=" + selected.trace().payloadCodePoints());
        }
    }
}
