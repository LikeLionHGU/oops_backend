package com.example.oops.analyzer;

import java.util.*;

/** Runtime assembly only; model execution remains in CandidateReviewEngine. */
final class RuntimeCandidateUnion {
    static final String REVISION="2026-10-20-runtime-rule-union-1";
    record Entry(String origin,String sourceId,String anchorId,String mechanismId,String candidateId,String state) {}
    record Trace(String revision,String state,String inputSha256,int totalEntries,boolean truncated,List<Entry> entries,
                 List<String> conflictingAnchorIds) {
        Trace(String revision,String state,String hash,int total,boolean truncated,List<Entry> entries) {
            this(revision,state,hash,total,truncated,entries,List.of());
        }
        Trace { entries=List.copyOf(entries);conflictingAnchorIds=List.copyOf(conflictingAnchorIds); }
    }
    record Selection(List<CandidateReviewEngine.Candidate> ai,List<MechanismVerificationBridge.Prepared> rules,Trace trace) {
        Selection { ai=List.copyOf(ai);rules=List.copyOf(rules); }
    }
    static Selection select(ReviewInput input,List<CandidateReviewEngine.Candidate> ai,int budget,
                            ReviewGuidelineLibrary library,int evidenceLimit) {
        var sources=ai.stream().map(c -> MechanismVerificationBridge.retainAi(input,c)).toList();
        var plan=CandidateUnionShadow.plan(input,sources.stream().map(MechanismVerificationBridge.AiSource::unionCandidate).toList(),budget);
        var preparation=plan.entries().stream().anyMatch(e -> e.candidate().origin()==CandidateUnionShadow.Origin.RULE
                && e.state()==CandidateUnionShadow.State.READY)?new MechanismVerificationBridge.Session(input):null;
        Map<String,CandidateReviewEngine.Candidate> originals=new HashMap<>();
        ai.forEach(c -> originals.put(c.candidateId(),c));
        List<CandidateReviewEngine.Candidate> selectedAi=new ArrayList<>();
        List<MechanismVerificationBridge.Prepared> rules=new ArrayList<>(); List<Entry> entries=new ArrayList<>();
        for(var entry:plan.entries()) {
            var c=entry.candidate(); String state=entry.state().name();
            String id=c.origin()==CandidateUnionShadow.Origin.AI?c.sourceId():MechanismVerificationBridge.ruleId(c);
            if(entry.state()==CandidateUnionShadow.State.READY) {
                if(c.origin()==CandidateUnionShadow.Origin.AI) selectedAi.add(originals.get(c.sourceId()));
                else try {
                    rules.add(preparation.prepare(new CandidateUnionShadow.Plan(plan.revision(),plan.inputSha256(),0,List.of(entry)),library,evidenceLimit).get(0));
                } catch(RuntimeException ex) { state="PREPARATION_FAILED"; }
            }
            entries.add(new Entry(c.origin().name(),c.sourceId(),c.anchorId(),c.mechanismId(),id,state));
        }
        var selectedIds=selectedAi.stream().map(CandidateReviewEngine.Candidate::candidateId).collect(java.util.stream.Collectors.toSet());
        // Selection fairness must not unnecessarily reorder unchanged AI verification batches.
        return new Selection(ai.stream().filter(c -> selectedIds.contains(c.candidateId())).toList(),rules,
                new Trace(REVISION,"PLANNED",plan.inputSha256(),entries.size(),false,entries));
    }
    static Trace bounded(Trace trace) {
        return new Trace(trace.revision(),trace.state(),trace.inputSha256(),trace.totalEntries(),
                trace.totalEntries()>200 || trace.conflictingAnchorIds().size()>200,trace.entries().stream()
                    .sorted(Comparator.comparing((Entry e) -> !"READY".equals(e.state()))).limit(200).toList(),
                trace.conflictingAnchorIds().stream().limit(200).toList());
    }
}
