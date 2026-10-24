package com.example.oops.analyzer;

import java.util.*;

/** Role-aligned lexical comparison, not inferred semantics or verified causal/speaker relations. */
final class ContextRelationRetrieval {
    public record Profile(String version, String basis, String sourceSha256, List<String> sourceIds,
                          String situation, String target, String evaluation, String linkage) {}
    record Query(List<ReviewInput.Segment> situation, List<ReviewInput.Segment> target,
                 List<ReviewInput.Segment> evaluation, List<ReviewInput.Segment> linkage) {
        Query { situation=List.copyOf(situation); target=List.copyOf(target); evaluation=List.copyOf(evaluation); linkage=List.copyOf(linkage); }
        static Query empty() { return new Query(List.of(),List.of(),List.of(),List.of()); }
        boolean present() { return !evaluation.isEmpty() && !linkage.isEmpty(); }
    }
    public record SelectedScore(String exampleId, double relationComponent) {}
    public record Trace(String revision, String basis, String querySha256, int situationLines,
                        int targetLines, int evaluationLines, int linkageLines, int availableProfiles,
                        List<SelectedScore> selectedScores) {}
    static Query from(CandidateReviewEngine.Candidate c) {
        var r = c.proposal().sourceRelations();
        if (r==null || r.failure(c.proposal().anchorId(),c.raw().stream().map(ReviewInput.Segment::id).toList())!=null)
            return Query.empty();
        Set<String> targets = new HashSet<>(r.subjectIds()); targets.addAll(r.referentIds());
        return new Query(lines(c.raw(),r.situationIds()),lines(c.raw(),targets),lines(c.raw(),r.evaluationIds()),lines(c.raw(),r.selectedIds()));
    }
    private static List<ReviewInput.Segment> lines(List<ReviewInput.Segment> raw,Collection<String> ids) {
        return raw.stream().filter(s -> ids.contains(s.id())).sorted(Comparator.comparingLong(ReviewInput.Segment::startMs)
                .thenComparing(ReviewInput.Segment::id)).toList();
    }
    static boolean valid(Profile p) {
        if (p==null) return true;
        return "authored-relation-search-1".equals(p.version()) && "AUTHORED_RETRIEVAL_NOT_SOURCE_EVIDENCE".equals(p.basis())
                && p.sourceSha256()!=null && p.sourceSha256().matches("[0-9a-f]{64}")
                && p.sourceIds()!=null && !p.sourceIds().isEmpty() && p.sourceIds().size()<=64
                && new HashSet<>(p.sourceIds()).size()==p.sourceIds().size()
                && p.sourceIds().stream().allMatch(id -> id!=null && id.matches("[A-Za-z0-9_-]{1,64}"))
                && field(p.situation(),false) && field(p.target(),false) && field(p.evaluation(),true) && field(p.linkage(),true);
    }
    private static boolean field(String s,boolean required) {
        return s!=null && s.codePointCount(0,s.length())<=350 && (!s.isBlank() || !required && s.isEmpty());
    }
    static double component(Profile p,Query q) {
        if (p==null || !q.present()) return 0;
        // Bounded experimental bonus, not a gate. An absent target does not reject expression-only cases.
        return .15*(dice(p.situation(),q.situation())+dice(p.target(),q.target())
                +dice(p.evaluation(),q.evaluation())+dice(p.linkage(),q.linkage()))/4;
    }
    private static double dice(String text,List<ReviewInput.Segment> lines) {
        Set<String> a=ContextExampleSelector.terms(text), b=new HashSet<>();
        // Never create a synthetic n-gram across disjoint quoted lines.
        lines.forEach(s -> b.addAll(ContextExampleSelector.terms(s.text())));
        if (a.isEmpty() || b.isEmpty()) return 0;
        long hits=a.stream().filter(b::contains).count(); return 2.0*hits/(a.size()+b.size());
    }
    static Trace trace(Query q,int profiles,List<ReviewGuidelineLibrary.ContextExample> selected) {
        return new Trace("2026-10-15-role-aligned-lexical-1","SOURCE_ROLE_IDS_SHAPE_VALIDATED_NOT_SEMANTIC_GOLD",
                ReviewGuidelineLibrary.retrievalHash(ReviewGuidelineLibrary.JSON.writeValueAsString(q)),
                q.situation().size(),q.target().size(),q.evaluation().size(),q.linkage().size(),profiles,
                selected.stream().map(e -> new SelectedScore(e.id(),component(e.relationProfile(),q))).toList());
    }
}
