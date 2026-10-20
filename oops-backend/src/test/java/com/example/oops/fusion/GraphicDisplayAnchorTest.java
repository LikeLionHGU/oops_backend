package com.example.oops.fusion;

import com.example.oops.domain.*;
import com.example.oops.dto.TimelineEventDto;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GraphicDisplayAnchorTest {
    RiskFinding finding(String id, long at, String text, double score, String other, long otherAt, String otherText) {
        var f = RiskFinding.builder().eventType(TimelineEventType.SPEECH).category(RiskCategory.GRAPHIC_METAPHOR)
                .source(EvidenceSource.SUBTITLE).startMs(at).endMs(at + 1000).text(text).score(score)
                .reason("검증된 연결의 기존 판단 이유").build();
        f.recordValidatedSupports(List.of(new FindingSupport(id, null, List.of(
                new FindingSupport.Quote(id, TimelineEventType.SPEECH, at, at + 1000, text, "PRIMARY"),
                new FindingSupport.Quote(other, TimelineEventType.SPEECH, otherAt, otherAt + 1000, otherText, "CONTEXT")))));
        return f;
    }
    @Test void displayUsesDirectImageEvenWhenContextCandidateHasHigherScore() {
        String context = "할머니가 해주시던 맛이야", direct = "사람의 피부를 찢는 것 같다";
        var a = finding("a", 1000, context, .9, "b", 3000, direct);
        var b = finding("b", 3000, direct, .5, "a", 1000, context);
        var expected = new ArrayList<FindingSupport>(); expected.addAll(a.validatedSupports()); expected.addAll(b.validatedSupports());
        var r = new FindingFusionService().fuse(List.of(a, b)).get(0);
        assertThat(r.getText()).isEqualTo(direct); assertThat(r.representativeStartMs()).isEqualTo(3000);
        assertThat(r.representativeEndMs()).isEqualTo(4000);
        assertThat(r.getScore()).isEqualTo(.9); assertThat(r.getReason()).isEqualTo("검증된 연결의 기존 판단 이유");
        assertThat(r.getStartMs()).isEqualTo(1000); assertThat(r.getEndMs()).isEqualTo(4000);
        assertThat(r.validatedSupports()).containsExactlyInAnyOrderElementsOf(expected);
        var dto = TimelineEventDto.from(r, null, null, null);
        assertThat(dto.text()).isEqualTo(direct); assertThat(dto.occurrences()).isOne(); assertThat(dto.supportCount()).isEqualTo(2);
        assertThat(dto.relatedEvidence()).hasSize(4);
    }
    @Test void twoDirectImagesAreAmbiguousAndKeepExistingRepresentative() {
        var a = finding("a", 1000, "팔을 잘라 버리는 것 같다", .9, "b", 3000, "피부를 찢는 것 같다");
        var b = finding("b", 3000, "피부를 찢는 것 같다", .5, "a", 1000, "팔을 잘라 버리는 것 같다");
        var r = new FindingFusionService().fuse(List.of(a, b)).get(0);
        assertThat(r.getText()).isEqualTo("팔을 잘라 버리는 것 같다"); assertThat(r.representativeStartMs()).isEqualTo(1000);
    }
    @Test void foodCompoundAndUnrecognisedWordingDoNotForceDisplayChoice() {
        var a = finding("a", 1000, "목살을 뜯는 것 같다", .9, "b", 3000, "낯선 표현입니다");
        var b = finding("b", 3000, "낯선 표현입니다", .5, "a", 1000, "목살을 뜯는 것 같다");
        assertThat(GraphicDisplayAnchor.choose(List.of(a.validatedSupports().get(0), b.validatedSupports().get(0)))).isEmpty();
        assertThat(new FindingFusionService().fuse(List.of(a, b)).get(0).getText()).isEqualTo("목살을 뜯는 것 같다");
    }
    @Test void contextOnlyMentionCannotBecomeAnInventedPrimary() {
        var a = finding("a", 1000, "앞선 설명", .9, "b", 3000, "살을 뜯는 것 같다");
        assertThat(GraphicDisplayAnchor.choose(a.validatedSupports())).isEmpty();
        assertThatThrownBy(() -> a.selectRepresentativeEvidence("outside")).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.getText()).isEqualTo("앞선 설명");
    }
    @Test void selectedPrimaryOrderAndTextSurvivePersistenceWithoutDroppingSupports() {
        var a = finding("a", 1000, "설명", .9, "b", 3000, "살을 뜯는 것 같다");
        var b = finding("b", 3000, "살을 뜯는 것 같다", .5, "a", 1000, "설명");
        var r = new FindingFusionService().fuse(List.of(a, b)).get(0);
        var loaded = RiskFinding.builder().eventType(TimelineEventType.SPEECH).text(r.getText()).build();
        org.springframework.test.util.ReflectionTestUtils.setField(loaded, "validatedSupportJson", r.getValidatedSupportJson());
        assertThat(loaded.getText()).isEqualTo("살을 뜯는 것 같다");
        assertThat(loaded.representativeStartMs()).isEqualTo(3000); assertThat(loaded.validatedSupports()).hasSize(2);
    }
}
