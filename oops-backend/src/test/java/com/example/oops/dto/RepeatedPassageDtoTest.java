package com.example.oops.dto;

import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RepeatedPassageDtoTest {
    private static final String RAW = "정확히 물리학이 어떻게 나아가는지도 모르면서";
    private RiskFinding finding(long id, long at, String text, String targetText) {
        var f = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.BELITTLEMENT)
                .source(EvidenceSource.SUBTITLE).startMs(at).endMs(at + 3000).text(text)
                .reason("original reason").target("label must not establish identity").score(.7).build();
        ReflectionTestUtils.setField(f, "id", id);
        var quotes = new ArrayList<FindingSupport.Quote>();
        quotes.add(new FindingSupport.Quote("s" + id, TimelineEventType.SPEECH, at, at + 3000, text, "PRIMARY"));
        if (targetText != null) quotes.add(new FindingSupport.Quote("s" + id, TimelineEventType.SPEECH, at, at + 3000, targetText, "TARGET"));
        f.recordValidatedSupports(List.of(new FindingSupport("s" + id, "GROUP", quotes)));
        return f;
    }
    @Test void repeatedExcerptProvidesTwoLocationsWithoutMutatingFindings() {
        var a = finding(1, 23000, RAW + " 이제 전과하는 걸 도피성 전과라고 하는데", RAW);
        var b = finding(2, 1148000, RAW, RAW);
        var before = List.of(a.getValidatedSupportJson(), b.getValidatedSupportJson());
        var group = RepeatedPassageDto.from(List.of(b, a));
        assertThat(group).hasSize(1);
        assertThat(group.get(0).basis()).isEqualTo("RAW_OVERLAP_ONLY_NOT_CONFIRMED_SAME_ISSUE");
        assertThat(group.get(0).locations()).extracting(RepeatedPassageDto.Location::startMs).containsExactly(23000L, 1148000L);
        assertThat(List.of(a.getValidatedSupportJson(), b.getValidatedSupportJson())).isEqualTo(before);
        assertThat(a.getStartMs()).isEqualTo(23000); assertThat(b.getStartMs()).isEqualTo(1148000);
        assertThat(a.getReason()).isEqualTo("original reason"); assertThat(a.getScore()).isEqualTo(.7);
    }
    @Test void proximityOrSameLabelOrShortWordsAreInsufficient() {
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW), finding(2, 5000, RAW, RAW)))).isEmpty();
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, "도피성 전과", "도피성 전과"),
                finding(2, 100000, "도피성 전과", "도피성 전과")))).isEmpty();
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW),
                finding(2, 100000, "다른 긴 발언이며 겹치는 쟁점을 확인할 수 없습니다", RAW)))).isEmpty();
    }
    @Test void missingOrConflictingTargetsAreNotGrouped() {
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW), finding(2, 100000, RAW, null)))).isEmpty();
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW), finding(2, 100000, RAW, "다른 사람")))).isEmpty();
    }
    @Test void extraUnmatchedTargetOrDifferentCategoryPreventsGrouping() {
        var a = finding(1, 1000, RAW, RAW); var b = finding(2, 100000, RAW, RAW);
        var qs = new ArrayList<>(b.validatedSupports().get(0).quotes());
        qs.add(new FindingSupport.Quote("other", TimelineEventType.SPEECH, 100000, 101000, "다른 집단", "TARGET"));
        b.recordValidatedSupports(List.of(new FindingSupport("s2", "GROUP", qs)));
        assertThat(RepeatedPassageDto.from(List.of(a, b))).isEmpty();
        b = finding(2, 100000, RAW, RAW);
        ReflectionTestUtils.setField(b, "category", RiskCategory.MOCKERY);
        assertThat(RepeatedPassageDto.from(List.of(a, b))).isEmpty();
    }
    @Test void normalizationDoesNotCorrectTranscriptionOrMergeParaphrases() {
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW),
                finding(2, 100000, RAW.replace("물리학", "물리악"), RAW.replace("물리학", "물리악"))))).isEmpty();
        assertThat(RepeatedPassageDto.from(List.of(finding(1, 1000, RAW, RAW),
                finding(2, 100000, RAW.replace(" ", ""), RAW.replace(" ", ""))))).hasSize(1);
    }
    @Test void emptyAndLegacyFindingsRemainUngrouped() {
        assertThat(RepeatedPassageDto.from(List.of())).isEmpty();
        var a = finding(1, 1000, RAW, RAW);
        ReflectionTestUtils.setField(a, "id", null);
        assertThat(RepeatedPassageDto.from(List.of(a, finding(2, 100000, RAW, RAW)))).isEmpty();
    }
}
