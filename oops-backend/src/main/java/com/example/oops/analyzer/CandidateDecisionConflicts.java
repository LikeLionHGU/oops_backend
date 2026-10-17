package com.example.oops.analyzer;

import java.util.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

/** Potential same-evidence disagreements only; does not establish equal meanings or adjudicate findings. */
final class CandidateDecisionConflicts {
    private record Scope(String anchor,String axis,String stage,List<String> evidence,List<String> permitted) {}
    private record Vote(String candidateId,Decision decision) {}
    private final Map<Scope,List<Vote>> votes=new LinkedHashMap<>();
    void record(CandidateReviewEngine.Candidate c,Observation o,String stage) {
        var scope=new Scope(c.proposal().anchorId(),c.proposal().axis(),stage,
                c.proposal().evidence().stream().map(CandidateReviewEngine.Quote::segmentId).distinct().sorted().toList(),
                c.raw().stream().map(ReviewInput.Segment::id).distinct().sorted().toList());
        // Diagnostic memory is bounded independently of operator-configured candidate budgets.
        if(!votes.containsKey(scope) && votes.size()>=200) return;
        var items=votes.computeIfAbsent(scope,k -> new ArrayList<>());
        if(items.size()<200 && items.stream().noneMatch(v -> v.candidateId().equals(c.candidateId())))
            items.add(new Vote(c.candidateId(),o.decision()));
    }
    List<CandidateReviewDiagnostics.DecisionConflictTrace> traces() {
        var result=new ArrayList<CandidateReviewDiagnostics.DecisionConflictTrace>();
        votes.forEach((scope,items) -> {
            if(items.stream().anyMatch(v -> v.decision()==Decision.PASS)
                    && items.stream().anyMatch(v -> v.decision()==Decision.REVIEW_REQUIRED))
                result.add(new CandidateReviewDiagnostics.DecisionConflictTrace(scope.anchor(),scope.axis(),scope.stage(),
                        items.stream().map(Vote::candidateId).toList(),items.stream().map(v -> v.decision().name()).toList(),
                        scope.evidence(),scope.permitted(),"SAME_EVIDENCE_POTENTIAL_DISAGREEMENT"));
        });
        return List.copyOf(result);
    }
}
