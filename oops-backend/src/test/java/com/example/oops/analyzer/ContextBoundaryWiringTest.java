package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.DefaultResourceLoader;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContextBoundaryWiringTest {
    @Test void productionSelectionSupplementsFrozenMissingBoundariesAndReportsTheChange() throws Exception {
        var root=Path.of("../datasets/controversy/experiments/speech-act-mechanism-20261023-verified");
        assumeTrue(Files.exists(root.resolve("preview-runtime.json")));
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,root.resolve("preview-runtime.json").toAbsolutePath().toUri().toString(),6000,3,3000,"",true);
        var baseline=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,root.resolve("preview-runtime.json").toAbsolutePath().toUri().toString(),6000,3,3000,"",true,false);
        library.load();baseline.load();
        int changes=0;
        for(var q:ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve("queries.json")))) {
            var raw=new ArrayList<ReviewInput.Segment>();
            for(var s:q.get("raw")) raw.add(new ReviewInput.Segment(s.path("id").asText(),SPEECH,s.path("startMs").asLong(),s.path("endMs").asLong(),s.path("text").asText(),null));
            var before=baseline.select(SPEECH,raw,"baseline");var after=library.select(SPEECH,raw,"connected");
            String expected=q.path("caseId").asText().equals("mimiminu-dev#victim-quotation")?"mimiminu-dev#victim-quotation":"speech-act-dev#reported-vs-enacted";
            assertThat(after.trace().exampleIds()).contains(expected);
            assertThat(after.trace().payloadCodePoints()).isLessThanOrEqualTo(6000);
            assertThat(after.trace().boundarySupplement()).isNotNull();
            if(after.trace().boundarySupplement().addedId()!=null) {
                changes++;
                assertThat(after.trace().exampleIds().get(0)).isEqualTo(before.trace().exampleIds().get(0));
                assertThat(after.trace().boundarySupplement().removedId()).isNotNull();
            }
        }
        assertThat(changes).isEqualTo(4);
    }
    @Test void approvedContrastIsTheOnlyReferenceChangeFromThePaidBaseline() throws Exception {
        var root=Path.of("../datasets/controversy");
        var frozen=root.resolve("experiments/current-context-20261023-plan/plan.json");
        assumeTrue(Files.exists(frozen));
        var before=ReviewGuidelineLibrary.JSON.readValue(Files.readAllBytes(frozen),CurrentContextReferenceTrial.Plan.class);
        var current=CurrentContextReferenceTrial.prepare(root);
        assertThat(current.requests()).hasSameSizeAs(before.requests());
        for(int i=0;i<current.requests().size();i++) {
            var a=before.requests().get(i);var b=current.requests().get(i);
            assertThat(b.caseId()).isEqualTo(a.caseId());
            assertThat(b.expectedDecision()).isEqualTo(a.expectedDecision());
            assertThat(ReviewGuidelineLibrary.JSON.readTree(b.input())).isEqualTo(ReviewGuidelineLibrary.JSON.readTree(a.input()));
            String marker="contextReference=";
            int first=a.system().indexOf(marker),second=b.system().indexOf(marker);
            assertThat(first).isPositive();assertThat(second).isPositive();
            assertThat(b.system().substring(0,second)).isEqualTo(a.system().substring(0,first));
            var expected = ReviewGuidelineLibrary.JSON.readTree(a.system().substring(first+marker.length()));
            var actual = ReviewGuidelineLibrary.JSON.readTree(b.system().substring(second+marker.length()));
            if (b.caseId().equals("audience-devaluation")) {
                var approved = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve(
                        "experiments/audience-boundary-draft-20261022/cases.json")));
                var control = java.util.stream.StreamSupport.stream(approved.get("normalDrafts").spliterator(), false)
                        .filter(c -> c.path("id").asText().equals("study-restart-encouragement")).findFirst().orElseThrow();
                assertThat(control.path("humanApproved").asBoolean()).isTrue();
                assertThat(control.path("expectedDecision").asText()).isEqualTo("PASS");
                String text = java.util.stream.StreamSupport.stream(control.get("raw").spliterator(), false)
                        .map(s -> s.path("text").asText()).collect(java.util.stream.Collectors.joining(" "));
                assertThat(expected.get("referenceContexts")).hasSize(1);
                ((tools.jackson.databind.node.ArrayNode) expected.at("/referenceContexts/0/context/normalContrasts"))
                        .add("합성 개발 대조(실제 관측 아님): " + text);
                assertThat(b.system().codePointCount(0,b.system().length()) - a.system().codePointCount(0,a.system().length()))
                        .isBetween(1, 350);
            }
            assertThat(actual).isEqualTo(expected);
        }
    }
    @Test void productionSupplementCannotRestoreExcludedFamily() throws Exception {
        var root=Path.of("../datasets/controversy/experiments/speech-act-mechanism-20261023-verified");
        assumeTrue(Files.exists(root.resolve("preview-runtime.json")));
        var library=new ReviewGuidelineLibrary(new DefaultResourceLoader(),true,root.resolve("preview-runtime.json").toAbsolutePath().toUri().toString(),6000,3,3000,"speech-act-development-boundaries",true);
        library.load();
        for(var q:ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(root.resolve("queries.json")))) {
            var raw=new ArrayList<ReviewInput.Segment>();
            for(var s:q.get("raw")) raw.add(new ReviewInput.Segment(s.path("id").asText(),SPEECH,s.path("startMs").asLong(),s.path("endMs").asLong(),s.path("text").asText(),null));
            var selection=library.select(SPEECH,raw,"excluded");
            assertThat(selection.trace().exampleIds()).doesNotContain("speech-act-dev#reported-vs-enacted");
            assertThat(selection.trace().boundarySupplement().addedId()).isNull();
        }
    }
}
