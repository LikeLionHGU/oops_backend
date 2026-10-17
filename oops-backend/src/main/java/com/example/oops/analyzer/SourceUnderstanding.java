package com.example.oops.analyzer;

import java.util.*;

/** Model-reported speech acts and source IDs, not verified facts, speaker identities or risk labels. */
public record SourceUnderstanding(List<ActReading> acts,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<RelationReading> relations) {
    static final Set<String> TYPES = Set.of("TERM_EXPLANATION", "QUOTED_SPEECH", "DIRECT_REQUEST",
            "SELF_DESCRIPTION", "SELF_OFFER", "EVALUATION", "OTHER", "UNRESOLVED");
    public SourceUnderstanding { acts = copy(acts); relations = copy(relations); }
    public SourceUnderstanding(List<ActReading> acts) { this(acts,null); }
    public record ActReading(String segmentId, String actType, List<String> contextIds) {
        public ActReading { contextIds = copy(contextIds); }
    }
    public record RelationReading(String segmentId,String actType,String relation,String respondsToId,String about,
                                  List<String> aboutIds,String actionOwner,List<String> ownerIds) {
        public RelationReading { aboutIds=copy(aboutIds); ownerIds=copy(ownerIds); }
    }
    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
    String failure(CandidateReviewEngine.Candidate candidate) {
        if (acts == null || acts.isEmpty() || acts.size() > candidate.raw().size() * TYPES.size())
            return "SOURCE_UNDERSTANDING_ACTS";
        Set<String> allowed = new HashSet<>(); candidate.raw().forEach(s -> allowed.add(s.id()));
        Set<List<String>> readings = new HashSet<>();
        for (var act : acts) {
            if (act == null || act.segmentId() == null || !allowed.contains(act.segmentId()))
                return "SOURCE_UNDERSTANDING_ID";
            if (act.actType() == null || !TYPES.contains(act.actType())) return "SOURCE_UNDERSTANDING_TYPE";
            if (!readings.add(List.of(act.segmentId(), act.actType()))) return "SOURCE_UNDERSTANDING_DUPLICATE";
            var ids = act.contextIds();
            if (ids == null || ids.size() > allowed.size() || ids.stream().anyMatch(id -> id == null || !allowed.contains(id)
                    || act.segmentId().equals(id)) || new HashSet<>(ids).size() != ids.size())
                return "SOURCE_UNDERSTANDING_CONTEXT";
        }
        if (acts.stream().noneMatch(a -> candidate.proposal().anchorId().equals(a.segmentId())))
            return "SOURCE_UNDERSTANDING_ANCHOR";
        return null;
    }
}
