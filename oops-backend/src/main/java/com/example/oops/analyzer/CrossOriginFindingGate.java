package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

/** Publication-only grouping of validated issues. Discovery axes and all source evidence remain intact. */
final class CrossOriginFindingGate {
    record Key(String anchor,String axis,String category,String target,TargetType type,TargetRelation relation,Set<EvidenceSpan> evidence) {}
    record Published(String candidateId,boolean rule) {}
    record Suppressed(String candidateId,String retainedCandidateId) {}
    private final Map<Key,List<Published>> published=new HashMap<>();
    private final List<Suppressed> suppressed=new ArrayList<>();
    private static final String GRAPHIC_ISSUE="FINAL_EXPRESSION:GRAPHIC_METAPHOR";
    boolean publish(String candidateId,boolean rule,String axis,Observation o) {
        if(o.decision()!=Decision.REVIEW_REQUIRED || o.details()==null || o.evidence().isEmpty())
            throw new IllegalArgumentException("Validated review observation required");
        var d=o.details(); var g=d.targetGrounding();
        boolean graphic="GRAPHIC_METAPHOR".equals(d.category()) && d.target()==null && g==null
                && Set.of("TARGET_TREATMENT","EXPRESSION_CONTENT").contains(axis);
        Set<EvidenceSpan> evidence=new HashSet<>(o.evidence());
        // Repeating the identical PRIMARY span as CONTEXT adds no distinct source evidence.
        // Do not erase other roles, quotes, offsets or context lines, even in this narrow issue.
        if(graphic) evidence.removeIf(e -> e.role()==EvidenceRole.CONTEXT && o.evidence().stream().anyMatch(p ->
                p.role()==EvidenceRole.PRIMARY && p.segmentId().equals(o.anchorId()) && p.segmentId().equals(e.segmentId())
                        && p.quote().equals(e.quote()) && p.start()==e.start() && p.end()==e.end()));
        var key=new Key(o.anchorId(),graphic?GRAPHIC_ISSUE:axis,d.category(),d.target(),g==null?null:g.type(),g==null?null:g.relation(),Set.copyOf(evidence));
        var previous=published.computeIfAbsent(key,ignored -> new ArrayList<>());
        var opposite=previous.stream().filter(p -> p.rule()!=rule).findFirst();
        if(opposite.isPresent()) {
            suppressed.add(new Suppressed(candidateId,opposite.get().candidateId()));return false;
        }
        previous.add(new Published(candidateId,rule));return true;
    }
    List<Suppressed> suppressed() { return List.copyOf(suppressed); }
    String retainedCandidateId(String candidateId) {
        return suppressed.stream().filter(s -> s.candidateId().equals(candidateId)).map(Suppressed::retainedCandidateId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Candidate was not grouped"));
    }
}
