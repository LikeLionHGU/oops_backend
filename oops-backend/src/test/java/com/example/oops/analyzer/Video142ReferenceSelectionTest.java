package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/** Local-only selection probe; does not replay a historical LLM request. */
class Video142ReferenceSelectionTest {
    @Test void reportsCurrentReferencesForUserReviewedWindow() throws Exception {
        Path root=Path.of("../datasets/controversy"),record=root.resolve("research/video-142-human-review-20261102.json"),runtime=root.resolve("guidelines/runtime.json");
        Assumptions.assumeTrue(Files.exists(record)&&Files.exists(runtime));var data=JSON.readTree(Files.readAllBytes(record));
        var item=data.path("reviews").get(2);assertThat(item.path("decision").asString()).isEqualTo("REVIEW_REQUIRED");
        var raw=new ArrayList<ReviewInput.Segment>();for(var s:item.path("transcriptSegments"))raw.add(new ReviewInput.Segment(
                s.path("id").asString(),TimelineEventType.SPEECH,s.path("startMs").asLong(),s.path("endMs").asLong(),s.path("text").asString(),null));
        var focus=raw.stream().filter(x->x.id().equals("stt-index-147")).toList();assertThat(focus).hasSize(1);
        var lib=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,runtime.toAbsolutePath().toUri().toString(),6000,3,3000,"",true,true);lib.load();
        assertThat(lib.trace(TimelineEventType.SPEECH).state()).isEqualTo("READY_WORKING_REFERENCE");
        var s=lib.select(TimelineEventType.SPEECH,raw,"local-142",focus,Set.of());
        System.out.println("VIDEO142_LOCAL_SELECTION="+JSON.writeValueAsString(Map.of("providerCalls",0,"historicalRequestReconstructed",false,
                "referenceIds",s.trace().exampleIds(),"retrievalMethod",s.trace().retrievalMethod(),"inputSegments",raw.size(),
                "selectedCodePoints",s.trace().payloadCodePoints(),"structuredTrace",s.trace().structuredSearch())));
    }
}
