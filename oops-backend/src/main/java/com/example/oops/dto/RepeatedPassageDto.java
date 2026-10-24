package com.example.oops.dto;

import com.example.oops.common.Ids;
import com.example.oops.domain.*;
import java.util.*;

/** Display-only raw overlap hints. Never merges findings, judgments, scores or review actions. */
public record RepeatedPassageDto(String basis, List<Location> locations) {
    public record Location(String eventId, String segmentId, long startMs, long endMs) {}

    public static List<RepeatedPassageDto> from(List<RiskFinding> findings) {
        var groups = new ArrayList<List<RiskFinding>>();
        var sorted = findings.stream().sorted(Comparator.comparingLong(RiskFinding::representativeStartMs)).toList();
        for (var finding : sorted) {
            var group = groups.stream().filter(g -> g.stream().allMatch(f -> matches(f, finding))).findFirst().orElse(null);
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(finding);
        }
        return groups.stream().filter(g -> g.size() > 1).map(g -> new RepeatedPassageDto(
                "RAW_OVERLAP_ONLY_NOT_CONFIRMED_SAME_ISSUE",
                g.stream().map(f -> {
                    var q = primary(f);
                    return new Location(Ids.of(f.getId()), q.segmentId(), q.startMs(), q.endMs());
                }).toList())).toList();
    }

    private static FindingSupport.Quote primary(RiskFinding f) {
        if (f.validatedSupports().size() != 1) return null;
        var s = f.validatedSupports().get(0);
        var quotes = s.quotes().stream().filter(q -> "PRIMARY".equals(q.role()) && s.anchorId().equals(q.segmentId())).toList();
        return quotes.size() == 1 ? quotes.get(0) : null;
    }

    private static boolean matches(RiskFinding a, RiskFinding b) {
        if (a.getId() == null || b.getId() == null || a.getId().equals(b.getId())
                || a.getEventType() != TimelineEventType.SPEECH || b.getEventType() != TimelineEventType.SPEECH
                || a.getCategory() != b.getCategory()) return false;
        var p = primary(a); var q = primary(b);
        if (p == null || q == null || p.type() != TimelineEventType.SPEECH || q.type() != TimelineEventType.SPEECH
                || p.endMs() < p.startMs() || q.endMs() < q.startMs()
                || Math.max(p.startMs(), q.startMs()) - Math.min(p.endMs(), q.endMs()) < 60000) return false;
        var x = a.validatedSupports().get(0); var y = b.validatedSupports().get(0);
        if (x.targetType() == null || !x.targetType().equals(y.targetType())) return false;
        String left = normalize(p.quote()), right = normalize(q.quote());
        String shared = left.length() <= right.length() ? left : right;
        if (shared.length() < 20 || !(left.contains(right) || right.contains(left))
                || shared.length() * 2 < Math.max(left.length(), right.length())) return false;
        // Require both validated target quotes to contain the shared literal passage.
        // Conflicting extra targets or missing targets prevent even advisory grouping.
        for (var support : List.of(x, y)) {
            var targets = support.quotes().stream().filter(e -> "TARGET".equals(e.role())).toList();
            if (targets.isEmpty() || targets.stream().anyMatch(e -> !normalize(e.quote()).contains(shared))) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        // No spelling correction, synonym mapping, or model interpretation.
        return value == null ? "" : value.replaceAll("[\\s\\p{P}]+", "");
    }
}
