package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.*;
import java.util.*;

/** Read-only pairwise evidence links, never identity, issue grouping or deduplication. */
public record RelatedFindingEvidenceDto(String basis, String leftEventId, String rightEventId,
        boolean targetLabelsDiffer, List<SharedEvidence> sharedEvidence) {
    public RelatedFindingEvidenceDto { sharedEvidence = List.copyOf(sharedEvidence); }
    public record SharedEvidence(String segmentId, long startMs, long endMs,
                                 List<String> leftRoles, List<String> rightRoles) {
        public SharedEvidence { leftRoles = List.copyOf(leftRoles); rightRoles = List.copyOf(rightRoles); }
    }
    private record Span(String id, long start, long end, String text) {}
    private record View(RiskFinding finding, FindingSupport support, FindingSupport.Quote primary,
                        Map<Span, Set<String>> roles) {}

    public static List<RelatedFindingEvidenceDto> from(List<RiskFinding> findings) {
        var views = findings.stream().map(RelatedFindingEvidenceDto::view).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong((View v) -> v.primary().startMs())
                        .thenComparing(v -> v.finding().getId())).toList();
        var result = new ArrayList<RelatedFindingEvidenceDto>();
        // Pairs only: A-B and B-C must never imply an A-C relationship.
        for (int i = 0; i < views.size(); i++) for (int j = i + 1; j < views.size(); j++) {
            var a = views.get(i); var b = views.get(j);
            if (b.primary().startMs() - a.primary().startMs() > 60_000) break;
            if (a.finding().getId().equals(b.finding().getId())
                    || !a.finding().getVideo().getId().equals(b.finding().getVideo().getId())
                    || a.finding().getCategory() != b.finding().getCategory()
                    || a.support().anchorId().equals(b.support().anchorId())
                    || Math.max(a.primary().endMs(), b.primary().endMs()) - a.primary().startMs() > 60_000) continue;
            var shared = a.roles().keySet().stream().filter(b.roles()::containsKey)
                    .sorted(Comparator.comparingLong(Span::start).thenComparing(Span::id)).toList();
            // Identical role duplicates on one raw segment count once, not as more evidence.
            if (shared.stream().map(Span::id).distinct().count() < 2) continue;
            boolean linkedAnchor = shared.stream().anyMatch(s ->
                    s.id().equals(a.support().anchorId()) && a.roles().get(s).contains("PRIMARY")
                            && b.roles().get(s).contains("CONTEXT")
                    || s.id().equals(b.support().anchorId()) && b.roles().get(s).contains("PRIMARY")
                            && a.roles().get(s).contains("CONTEXT"));
            if (!linkedAnchor) continue;
            result.add(new RelatedFindingEvidenceDto("SHARED_VALIDATED_EVIDENCE_NOT_CONFIRMED_SAME_ISSUE",
                    Ids.of(a.finding().getId()), Ids.of(b.finding().getId()),
                    !Objects.equals(a.finding().getTarget(), b.finding().getTarget()),
                    shared.stream().map(s -> new SharedEvidence(s.id(), s.start(), s.end(),
                            a.roles().get(s).stream().sorted().toList(),
                            b.roles().get(s).stream().sorted().toList())).toList()));
        }
        return List.copyOf(result);
    }

    private static View view(RiskFinding f) {
        if (f == null || f.getId() == null || f.getVideo() == null || f.getVideo().getId() == null
                || f.getCategory() == null || f.getEventType() != TimelineEventType.SPEECH) return null;
        var supports = f.validatedSupports();
        if (supports.size() != 1) return null;
        var s = supports.get(0);
        if (s.anchorId() == null || s.anchorId().isBlank()) return null;
        var primaries = s.quotes().stream().filter(q -> q != null && "PRIMARY".equals(q.role())).toList();
        if (primaries.size() != 1 || !s.anchorId().equals(primaries.get(0).segmentId())) return null;
        Map<Span, Set<String>> roles = new LinkedHashMap<>();
        Map<String, Span> byId = new HashMap<>();
        for (var q : s.quotes()) {
            if (q == null || q.type() != TimelineEventType.SPEECH || q.segmentId() == null || q.segmentId().isBlank()
                    || q.quote() == null || q.quote().isBlank() || q.startMs() < 0 || q.endMs() < q.startMs()
                    || q.role() == null || !Set.of("PRIMARY", "CONTEXT", "TARGET").contains(q.role())) return null;
            var span = new Span(q.segmentId(), q.startMs(), q.endMs(), q.quote());
            var previous = byId.putIfAbsent(q.segmentId(), span);
            if (previous != null && !previous.equals(span)) return null;
            roles.computeIfAbsent(span, ignored -> new HashSet<>()).add(q.role());
        }
        // targetType and display label are intentionally not identity evidence here.
        return new View(f, s, primaries.get(0), roles);
    }
}
