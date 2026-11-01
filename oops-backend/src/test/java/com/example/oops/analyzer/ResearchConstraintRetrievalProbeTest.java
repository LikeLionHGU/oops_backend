package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Local retrieval observation only: approved synthetic inputs, no model, no accuracy claim. */
class ResearchConstraintRetrievalProbeTest {
    @Test void inspectCurrentReferenceSelectionForTwoApprovedSyntheticControls() throws Exception {
        var path = Path.of("../datasets/controversy/guidelines/runtime.json");
        Assumptions.assumeTrue(Files.isRegularFile(path));
        var controlsPath = ResearchCaseAliases.datasetFile("contextControlsPath");
        Assumptions.assumeTrue(Files.isRegularFile(controlsPath));
        var packet = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(controlsPath));
        assertThat(packet.get("status").asString()).isEqualTo("USER_APPROVED_DEVELOPMENT_ONLY");
        assertThat(packet.get("controls").size()).isEqualTo(2);
        var library = new ReviewGuidelineLibrary(new DefaultResourceLoader(), true,
                path.toUri().toString(), 6000, 3, 3000, "", true, true);
        library.load();
        assertThat(library.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        for (int i = 0; i < packet.get("controls").size(); i++) {
            var item = packet.get("controls").get(i);
            assertThat(item.get("humanApproved").asBoolean()).isTrue();
            assertThat(item.get("dialogue").size()).isEqualTo(2);
            var raw = List.of(new ReviewInput.Segment("a", TimelineEventType.SPEECH, 0, 1000, item.get("dialogue").get(0).asString(), null),
                    new ReviewInput.Segment("b", TimelineEventType.SPEECH, 1000, 2000, item.get("dialogue").get(1).asString(), null));
            var selection = library.select(TimelineEventType.SPEECH, raw, "synthetic-draft-" + (i + 1));
            assertThat(selection.trace().state()).isEqualTo("READY_WORKING_REFERENCE");
            System.out.println("RETRIEVAL_DRAFT_" + (i + 1) + "=" + selection.trace().exampleIds());
        }
    }
}
