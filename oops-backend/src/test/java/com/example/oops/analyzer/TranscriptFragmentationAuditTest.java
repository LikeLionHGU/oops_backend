package com.example.oops.analyzer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.oops.domain.TimelineEventType.SPEECH;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Legacy and current planner replay. No provider, generated interpretation or decision labels. */
class TranscriptFragmentationAuditTest {
    private static final Path ROOT = Path.of("../datasets/controversy");
    private ReviewInput input(Path file) throws Exception {
        var rows = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(file)).get("segments");
        List<ReviewInput.Segment> raw = new ArrayList<>(); int i = 0;
        for (var r : rows) raw.add(new ReviewInput.Segment("stt-index-" + i++, SPEECH,
                r.path("startMs").asLong(),r.path("endMs").asLong(),r.path("text").asText(),null));
        return new ReviewInput(raw);
    }
    private List<String> ids(List<ReviewInput.Segment> raw) {
        return raw.stream().map(ReviewInput.Segment::id).toList();
    }
    private Map<String,Object> audit(ReviewInput input) {
        List<Map<String,Object>> rows = new ArrayList<>(); int number = 0;
        for (var batch : TextReviewBatchPlanner.plan(input,SPEECH,3)) {
            List<ReviewInput.Segment> raw = new ArrayList<>(batch.primary()); raw.addAll(batch.context());
            raw.sort(Comparator.comparingLong(ReviewInput.Segment::startMs));
            var windows = CandidateContextWindow.conversationUnits(batch);
            List<Map<String,Object>> focuses = new ArrayList<>();
            for (String focus : List.of("stt-index-17","stt-index-29","stt-index-49")) {
                var granted = windows.stream().filter(w -> w.anchorId().equals(focus)).toList();
                if (!granted.isEmpty()) focuses.add(Map.of("anchorId",focus,"windows",granted.stream()
                        .map(w -> Map.of("ids",w.segmentIds(),"limited",w.limited(),"spanMs",w.endMs()-w.startMs())).toList()));
            }
            rows.add(Map.of("batch",++number,"primaryIds",ids(batch.primary()),"rawIds",ids(raw),
                    "rawCodePoints",raw.stream().mapToInt(s -> s.text().codePointCount(0,s.text().length())).sum(),
                    "contextLimited",batch.contextLimited(),"focuses",focuses));
        }
        return Map.of("inputSegments",input.segments().size(),"batches",rows);
    }
    @Test void reproducesLegacyCountDrivenFragmentation() throws Exception {
        var oldFile=ROOT.resolve("experiments/podcast-five-live-20261025/request-childhood-confrontation.json");
        var newFile=ROOT.resolve("experiments/podcast-meaning-edit-20261025/import-request-corrected-meaning.json");
        assumeTrue(Files.exists(oldFile)&&Files.exists(newFile));
        var old=input(oldFile); var granular=input(newFile);
        assertThat(old.segments()).hasSize(14); assertThat(granular.segments()).hasSize(58);
        assertThat(granular.segments().get(57).endMs()-granular.segments().get(0).startMs()).isEqualTo(94000);
        assertThat(granular.segments().stream().mapToInt(s -> s.text().codePointCount(0,s.text().length())).sum()).isLessThan(6000);
        assertThat(TextReviewBatchPlanner.plan(old,SPEECH,3)).hasSize(1);
        var batches=TextReviewBatchPlanner.plan(granular,SPEECH,3);
        assertThat(batches).hasSize(4).allSatisfy(b -> {
            assertThat(b.primary()).hasSizeLessThanOrEqualTo(20);
            assertThat(b.context()).hasSizeLessThanOrEqualTo(16);
            var supplied=new HashSet<>(ids(b.primary()));supplied.addAll(ids(b.context()));
            assertThat(ids(granular.segments())).containsAll(supplied);
            assertThat(supplied.contains("stt-index-0")&&supplied.contains("stt-index-57")).isFalse();
        });
        // Enlarging batching alone would still encounter the independently enforced 48-line window cap.
        var hypothetical=new TextReviewBatchPlanner.Batch(granular.segments(),List.of(),false,false);
        assertThat(CandidateContextWindow.conversationUnits(hypothetical)).isNotEmpty().allSatisfy(w -> {
            assertThat(w.segmentIds()).hasSizeLessThanOrEqualTo(48);
            assertThat(new HashSet<>(w.segmentIds()).containsAll(ids(granular.segments()))).isFalse();
        });
        String output=System.getenv("FRAGMENTATION_AUDIT_OUTPUT");
        if(output!=null){
            var file=Path.of(output).toAbsolutePath();
            assertThat(file.getParent().toRealPath()).startsWith(ROOT.resolve("experiments").toRealPath());
            Files.writeString(file,ReviewGuidelineLibrary.JSON.writeValueAsString(Map.of(
                    "providerCalls",0,"scope","PLANNER_WINDOW_SHAPE_ONLY_NOT_ASR_OR_SEMANTIC_ACCURACY",
                    "oldScript",audit(old),"nativeMeaningEdit",audit(granular))),StandardOpenOption.CREATE_NEW);
        }
    }

    @Test void currentPagesPreserveAllFiftyEightStoredAtomsInOneSharedWindow() throws Exception {
        var file=ROOT.resolve("experiments/podcast-meaning-edit-20261025/import-request-corrected-meaning.json");
        assumeTrue(Files.exists(file));
        var source=input(file);
        var pages=DialogueReadingPage.plan(source);
        assertThat(source.segments()).hasSize(58);
        assertThat(pages).hasSize(1);
        var page=pages.get(0);
        assertThat(page.primary()).containsExactlyElementsOf(source.segments());
        assertThat(page.contextLimited()).isFalse();
        assertThat(page.oversizedPrimary()).isFalse();
        assertThat(DialogueReadingPage.inputCodePoints(DialogueReadingPage.raw(page),page.primary())).isLessThan(24000);
        assertThat(CandidateContextWindow.conversationUnits(page)).hasSize(58).allSatisfy(w ->
                assertThat(w.segmentIds()).containsExactlyElementsOf(ids(source.segments())));
    }
}
