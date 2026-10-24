package com.example.oops.analyzer;

import com.example.oops.domain.TimelineEventType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DialogueReadingPageTest {
    private ReviewInput input(int count, long step, String text) {
        List<ReviewInput.Segment> atoms = new ArrayList<>();
        for (int i=0;i<count;i++) atoms.add(new ReviewInput.Segment("atom-"+i,
                TimelineEventType.SPEECH,i*step,i*step+1000,text,null));
        return new ReviewInput(atoms);
    }

    @Test void fiftyEightShortAtomsAreOneLosslessPageForBothAxes() {
        var input=input(58,1600,"원문 그대로 😀");
        var pages=DialogueReadingPage.plan(input);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).primary()).containsExactlyElementsOf(input.segments());
        assertThat(pages.get(0).contextLimited()).isFalse();
        var windows=CandidateContextWindow.conversationUnits(pages.get(0));
        assertThat(windows).hasSize(58);
        var expected=input.segments().stream().map(ReviewInput.Segment::id).toList();
        windows.forEach(w -> assertThat(w.segmentIds()).containsExactlyElementsOf(expected));
    }

    @Test void longInputCoversEachPrimaryExactlyOnceWithBoundedSharedContext() {
        var input=input(500,2000,"변경 없는 대화");
        var pages=DialogueReadingPage.plan(input);
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages.stream().flatMap(p -> p.primary().stream()).toList())
                .containsExactlyElementsOf(input.segments());
        for (var page:pages) {
            var raw=DialogueReadingPage.raw(page);
            assertThat(raw.size()).isLessThanOrEqualTo(DialogueReadingPage.MAX_ATOMS);
            assertThat(DialogueReadingPage.inputCodePoints(raw,page.primary()))
                    .isLessThanOrEqualTo(DialogueReadingPage.MAX_INPUT_CODE_POINTS);
            assertThat(CandidateContextWindow.conversationUnits(page)).allSatisfy(w -> {
                assertThat(w.endMs()-w.startMs()).isLessThanOrEqualTo(DialogueReadingPage.MAX_SPAN_MS);
                assertThat(w.segmentIds()).containsExactlyElementsOf(raw.stream().map(ReviewInput.Segment::id).toList());
            });
        }
    }

    @Test void hardGapNeverBringsUnrelatedRawIntoThePage() {
        var input=input(3,15000,"서로 떨어진 발언");
        var pages=DialogueReadingPage.plan(input);
        assertThat(pages).hasSize(3);
        pages.forEach(p -> {assertThat(p.context()).isEmpty();assertThat(DialogueReadingPage.raw(p)).hasSize(1);});
    }

    @Test void oversizedAtomIsPreservedButNotAdvertisedAsAnAdmissibleWindow() {
        var input=input(1,1000,"😀".repeat(6001));
        var page=DialogueReadingPage.plan(input).get(0);
        assertThat(page.primary()).containsExactlyElementsOf(input.segments());
        assertThat(page.oversizedPrimary()).isTrue();
        assertThat(DialogueReadingPage.windows(page)).isEmpty();
    }

    @Test void unicodeAndDenseAtomsRespectSafetyCapsWithoutDroppingSource() {
        for (var input:List.of(input(300,1,"한"),input(40,1000,"😀".repeat(200)),
                input(40,1000,"\\\"".repeat(140)))) {
            var pages=DialogueReadingPage.plan(input);
            assertThat(pages.stream().flatMap(p -> p.primary().stream()).toList())
                    .containsExactlyElementsOf(input.segments());
            for(var page:pages) {
                var raw=DialogueReadingPage.raw(page);
                assertThat(raw.size()).isLessThanOrEqualTo(128);
                assertThat(raw.stream().mapToInt(s -> s.text().codePointCount(0,s.text().length())).sum()).isLessThanOrEqualTo(6000);
                assertThat(DialogueReadingPage.inputCodePoints(raw,page.primary())).isLessThanOrEqualTo(24000);
            }
        }
    }

    @Test void overlappingSpeechUsesCoveredEndRatherThanAdjacentShortAtomEnd() {
        var input=new ReviewInput(List.of(
                new ReviewInput.Segment("a",TimelineEventType.SPEECH,0,20000,"긴 발언",null),
                new ReviewInput.Segment("b",TimelineEventType.SPEECH,1000,2000,"겹친 발언",null),
                new ReviewInput.Segment("c",TimelineEventType.SPEECH,15000,16000,"뒤 발언",null)));
        assertThat(DialogueReadingPage.plan(input)).hasSize(1);
    }
}
