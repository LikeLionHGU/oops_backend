package com.example.oops.dto;

import com.example.oops.domain.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RelatedFindingEvidenceDtoTest {
    private FindingSupport.Quote q(String id, long at, String role) {
        return new FindingSupport.Quote(id, TimelineEventType.SPEECH, at, at + 500, "합성 원문 " + id, role);
    }
    private RiskFinding f(long id, String anchor, String target, FindingSupport.Quote... quotes) {
        var video = Video.builder().build(); ReflectionTestUtils.setField(video, "id", 1L);
        var primary = Arrays.stream(quotes).filter(q -> "PRIMARY".equals(q.role())).findFirst().orElse(q(anchor, 0, "PRIMARY"));
        var f = RiskFinding.builder().video(video).eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.BELITTLEMENT).source(EvidenceSource.SUBTITLE)
                .startMs(primary.startMs()).endMs(primary.endMs()).text(primary.quote())
                .reason("unchanged reason").target(target).score(.7).build();
        ReflectionTestUtils.setField(f, "id", id);
        f.recordValidatedSupports(List.of(new FindingSupport(anchor, "PERSON", List.of(quotes))));
        return f;
    }
    private List<RiskFinding> pair() {
        return List.of(f(1, "a", "당신", q("a", 1000, "PRIMARY"), q("a", 1000, "TARGET"),
                        q("b", 2000, "CONTEXT"), q("c", 3000, "CONTEXT")),
                f(2, "b", "너", q("b", 2000, "PRIMARY"), q("b", 2000, "TARGET"),
                        q("a", 1000, "CONTEXT"), q("c", 3000, "CONTEXT")));
    }
    @Test void reportsSharedEvidenceWithoutMergingOrChangingFindings() {
        var pair = pair();
        var before = pair.stream().map(f -> List.of(f.getValidatedSupportJson(), f.getText(), f.getTarget(),
                f.getReason(), f.getStartMs(), f.getEndMs(), f.getScore(), f.getMergedCount())).toList();
        var links = RelatedFindingEvidenceDto.from(List.of(pair.get(1), pair.get(0)));
        assertThat(links).hasSize(1);
        var link = links.get(0);
        assertThat(link.basis()).isEqualTo("SHARED_VALIDATED_EVIDENCE_NOT_CONFIRMED_SAME_ISSUE");
        assertThat(link.leftEventId()).isEqualTo("1"); assertThat(link.rightEventId()).isEqualTo("2");
        assertThat(link.targetLabelsDiffer()).isTrue();
        assertThat(link.sharedEvidence()).extracting(RelatedFindingEvidenceDto.SharedEvidence::segmentId)
                .containsExactly("a", "b", "c");
        assertThat(link.sharedEvidence().get(0).leftRoles()).containsExactly("PRIMARY", "TARGET");
        assertThat(pair.stream().map(f -> List.of(f.getValidatedSupportJson(), f.getText(), f.getTarget(),
                f.getReason(), f.getStartMs(), f.getEndMs(), f.getScore(), f.getMergedCount())).toList()).isEqualTo(before);
    }
    @Test void proximitySameLabelOrOneSharedSegmentDoesNotCreateLink() {
        var a = f(1, "a", "same", q("a", 1000, "PRIMARY"), q("a", 1000, "TARGET"));
        var b = f(2, "b", "same", q("b", 2000, "PRIMARY"), q("a", 1000, "CONTEXT"));
        assertThat(RelatedFindingEvidenceDto.from(List.of(a, b))).isEmpty();
    }
    @Test void twoSharedContextLinesWithoutAnchorLinkAreInsufficient() {
        var a = f(1, "a", "same", q("a", 1000, "PRIMARY"), q("x", 3000, "CONTEXT"), q("y", 4000, "CONTEXT"));
        var b = f(2, "b", "same", q("b", 2000, "PRIMARY"), q("x", 3000, "CONTEXT"), q("y", 4000, "CONTEXT"));
        assertThat(RelatedFindingEvidenceDto.from(List.of(a, b))).isEmpty();
    }
    @Test void roleDuplicatesDoNotCountAsTwoSourceSegments() {
        var a = f(1, "a", "a", q("a", 1000, "PRIMARY"), q("a", 1000, "TARGET"));
        var b = f(2, "b", "b", q("b", 2000, "PRIMARY"), q("a", 1000, "CONTEXT"), q("a", 1000, "TARGET"));
        assertThat(RelatedFindingEvidenceDto.from(List.of(a, b))).isEmpty();
    }
    @Test void differentTargetsAndTypesRemainOnlyEvidenceLinks() {
        var pair = pair(); var b = pair.get(1); var support = b.validatedSupports().get(0);
        b.recordValidatedSupports(List.of(new FindingSupport(support.anchorId(), "ACTIVITY", support.quotes())));
        assertThat(RelatedFindingEvidenceDto.from(pair)).hasSize(1);
        assertThat(b.getTarget()).isEqualTo("너");
    }
    @Test void categoryVideoAndChannelBoundariesPreventLinks() {
        for (String change : List.of("category", "video", "channel")) {
            var pair = pair(); var b = pair.get(1);
            if (change.equals("category")) ReflectionTestUtils.setField(b, "category", RiskCategory.MOCKERY);
            if (change.equals("video")) ReflectionTestUtils.setField(b.getVideo(), "id", 2L);
            if (change.equals("channel")) ReflectionTestUtils.setField(b, "eventType", TimelineEventType.CAPTION);
            assertThat(RelatedFindingEvidenceDto.from(pair)).as(change).isEmpty();
        }
    }
    @Test void invalidLegacyOrMultipleSupportsAreNotLinked() {
        for (String change : List.of("legacy", "multiple", "role", "time", "anchor", "quote")) {
            var pair = pair(); var b = pair.get(1); var s = b.validatedSupports().get(0);
            if (change.equals("legacy")) ReflectionTestUtils.setField(b, "validatedSupportJson", null);
            if (change.equals("multiple")) b.recordValidatedSupports(List.of(s, s));
            if (change.equals("anchor")) b.recordValidatedSupports(List.of(new FindingSupport("other", "PERSON", s.quotes())));
            if (List.of("role", "time", "quote").contains(change)) {
                var quotes = new ArrayList<>(s.quotes()); var q = quotes.get(0);
                quotes.set(0, new FindingSupport.Quote(q.segmentId(), q.type(), change.equals("time") ? -1 : q.startMs(),
                        q.endMs(), change.equals("quote") ? "" : q.quote(), change.equals("role") ? null : q.role()));
                b.recordValidatedSupports(List.of(new FindingSupport(s.anchorId(), s.targetType(), quotes)));
            }
            assertThat(RelatedFindingEvidenceDto.from(pair)).as(change).isEmpty();
        }
    }
    @Test void distantPrimariesAreNotLinkedEvenWithMatchingEvidence() {
        var a = f(1, "a", "a", q("a", 1000, "PRIMARY"), q("b", 70000, "CONTEXT"));
        var b = f(2, "b", "b", q("b", 70000, "PRIMARY"), q("a", 1000, "CONTEXT"));
        assertThat(RelatedFindingEvidenceDto.from(List.of(a, b))).isEmpty();
    }
    @Test void pairsDoNotBecomeTransitiveIssueGroups() {
        var a = f(1, "a", "a", q("a", 1000, "PRIMARY"), q("b", 2000, "CONTEXT"), q("x", 4000, "CONTEXT"));
        var b = f(2, "b", "b", q("b", 2000, "PRIMARY"), q("a", 1000, "CONTEXT"), q("c", 3000, "CONTEXT"),
                q("x", 4000, "CONTEXT"), q("y", 5000, "CONTEXT"));
        var c = f(3, "c", "c", q("c", 3000, "PRIMARY"), q("b", 2000, "CONTEXT"), q("y", 5000, "CONTEXT"));
        assertThat(RelatedFindingEvidenceDto.from(List.of(a, b, c)))
                .extracting(l -> l.leftEventId() + "-" + l.rightEventId()).containsExactly("1-2", "2-3");
    }
    @Test void saved126EvidenceProducesTwoAdvisoryPairsNotMergedCards() throws Exception {
        var file = Path.of("../datasets/controversy/experiments/podcast-replay-124-57casebook-20261024/report-126.json");
        assumeTrue(Files.exists(file));
        var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        var findings = new ArrayList<RiskFinding>();
        for (var e : root.at("/data/events")) {
            var qs = new ArrayList<FindingSupport.Quote>(); String anchor = null;
            for (var q : e.path("relatedEvidence")) {
                qs.add(new FindingSupport.Quote(q.path("segmentId").asText(), TimelineEventType.valueOf(q.path("type").asText()),
                        q.path("startMs").asLong(), q.path("endMs").asLong(), q.path("quote").asText(), q.path("role").asText()));
                if ("PRIMARY".equals(q.path("role").asText())) anchor = q.path("segmentId").asText();
            }
            var f = f(Long.parseLong(e.path("id").asText()), anchor, "unknown display target", qs.toArray(FindingSupport.Quote[]::new));
            ReflectionTestUtils.setField(f, "category", RiskCategory.valueOf(e.path("riskTypes").get(0).asText()));
            // The saved public DTO omits targetType: no identity is invented for this replay.
            f.recordValidatedSupports(List.of(new FindingSupport(anchor, null, qs)));
            findings.add(f);
        }
        assertThat(RelatedFindingEvidenceDto.from(findings)).extracting(l -> l.leftEventId() + "-" + l.rightEventId())
                .containsExactly("216-215", "213-214");
        assertThat(findings).hasSize(5);
    }
}
