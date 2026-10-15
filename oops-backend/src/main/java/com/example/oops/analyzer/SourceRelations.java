package com.example.oops.analyzer;

import java.util.*;

/** Raw ID roles only: not a proven relationship, speaker identity, target or risk judgment. */
record SourceRelations(List<String> situationIds, List<String> subjectIds, List<String> actionIds,
                       List<String> evaluationIds, List<String> referentIds) {
    SourceRelations {
        situationIds=copy(situationIds); subjectIds=copy(subjectIds); actionIds=copy(actionIds);
        evaluationIds=copy(evaluationIds); referentIds=copy(referentIds);
    }
    private static List<String> copy(List<String> ids) {
        return ids==null?null:Collections.unmodifiableList(new ArrayList<>(ids));
    }
    String failure(String anchor, Collection<String> evidenceIds) {
        List<List<String>> roles=Arrays.asList(situationIds,subjectIds,actionIds,evaluationIds,referentIds);
        if(roles.stream().anyMatch(Objects::isNull)) return "PROPOSAL_RELATION_FIELDS";
        if(roles.stream().anyMatch(ids->ids.size()>8 || ids.stream().anyMatch(id->id==null||id.isBlank())
                || new HashSet<>(ids).size()!=ids.size())) return "PROPOSAL_RELATION_IDS";
        if(evaluationIds.isEmpty() || !evaluationIds.contains(anchor)) return "PROPOSAL_RELATION_ANCHOR";
        if(roles.stream().flatMap(Collection::stream).anyMatch(id->!evidenceIds.contains(id)))
            return "PROPOSAL_RELATION_NOT_IN_EVIDENCE";
        return null;
    }
}
