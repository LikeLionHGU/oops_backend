package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

/** Publication-only exact duplicates. Every evaluation and source trace remains intact. */
final class CrossOriginFindingGate {
    record Key(String anchor,String axis,String category,String target,TargetType type,TargetRelation relation,Set<EvidenceSpan> evidence) {}
    record Published(String candidateId,boolean rule) {}
    record Suppressed(String candidateId,String retainedCandidateId) {}
    private final Map<Key,List<Published>> published=new HashMap<>();
    private final List<Suppressed> suppressed=new ArrayList<>();
    boolean publish(String candidateId,boolean rule,String axis,Observation o) {
        if(o.decision()!=Decision.REVIEW_REQUIRED || o.details()==null || o.evidence().isEmpty())
            throw new IllegalArgumentException("Validated review observation required");
        var d=o.details(); var g=d.targetGrounding();
        var key=new Key(o.anchorId(),axis,d.category(),d.target(),g==null?null:g.type(),g==null?null:g.relation(),Set.copyOf(o.evidence()));
        var previous=published.computeIfAbsent(key,ignored -> new ArrayList<>());
        var opposite=previous.stream().filter(p -> p.rule()!=rule).findFirst();
        if(opposite.isPresent()) {
            suppressed.add(new Suppressed(candidateId,opposite.get().candidateId()));return false;
        }
        previous.add(new Published(candidateId,rule));return true;
    }
    List<Suppressed> suppressed() { return List.copyOf(suppressed); }
}
