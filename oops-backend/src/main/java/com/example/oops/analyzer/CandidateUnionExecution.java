package com.example.oops.analyzer;

import java.util.*;

/** In-memory execution seam for mock/saved verifiers. No production client, Spring wiring or publication. */
final class CandidateUnionExecution {
    static final String REVISION="2026-10-20-candidate-union-execution-1";
    enum State { PASS, REVIEW_REQUIRED, UNCERTAIN, PREPARATION_FAILED, VERIFICATION_FAILED,
                 BUDGET_SKIPPED, CONTEXT_INVALID, CANCELLED }
    @FunctionalInterface interface RuleVerifier {
        CandidateReviewEngine.VerificationResult verify(MechanismVerificationBridge.Prepared request);
    }
    @FunctionalInterface interface AiVerifier {
        CandidateReviewEngine.VerificationResult verify(CandidateReviewEngine.Candidate original);
    }
    record Outcome(CandidateUnionShadow.Origin origin,String sourceId,String anchorId,State state,
                   String failureCode,int verifierAttempts,String rawSha256,String referenceSha256,
                   ReviewEvaluation.Observation observation) {}
    record Result(String revision,CandidateUnionShadow.Plan plan,List<Outcome> outcomes,
                  int verifierAttempts,boolean incompleteCandidates,List<String> conflictingAnchorIds) {
        Result { outcomes=List.copyOf(outcomes); conflictingAnchorIds=List.copyOf(conflictingAnchorIds); }
    }

    static Result run(ReviewInput input,List<CandidateReviewEngine.Candidate> aiSources,
                      ReviewGuidelineLibrary library,int budget,int evidenceLimit,
                      RuleVerifier ruleVerifier,AiVerifier aiVerifier) {
        if(evidenceLimit<0) throw new IllegalArgumentException("Invalid evidence limit");
        Map<String,MechanismVerificationBridge.AiSource> originals=new LinkedHashMap<>();
        List<CandidateUnionShadow.AiCandidate> ai=new ArrayList<>();
        for(var source:aiSources) {
            var retained=MechanismVerificationBridge.retainAi(input,source);
            var previous=originals.putIfAbsent(source.candidateId(),retained);
            if(previous!=null && !previous.original().equals(source))
                throw new IllegalArgumentException("Same source ID with different AI proposal/window");
            ai.add(retained.unionCandidate());
        }
        var plan=CandidateUnionShadow.plan(input,ai,budget);
        if(plan.entries().stream().anyMatch(e -> e.state()==CandidateUnionShadow.State.READY
                && (e.candidate().origin()==CandidateUnionShadow.Origin.RULE?ruleVerifier==null:aiVerifier==null)))
            throw new IllegalArgumentException("Ready candidates require their verifier port");
        List<Outcome> outcomes=new ArrayList<>();
        for(var entry:plan.entries()) {
            var c=entry.candidate();
            if(entry.state()!=CandidateUnionShadow.State.READY) {
                outcomes.add(outcome(c,State.valueOf(entry.state().name()),null,0,null,null,null)); continue;
            }
            if(Thread.currentThread().isInterrupted()) {
                outcomes.add(outcome(c,State.CANCELLED,"INTERRUPTED",0,null,null,null)); continue;
            }
            String rawHash=null,referenceHash=null; int attempts=0; boolean preparing=true;
            try {
                CandidateReviewEngine.Validation validation;
                if(c.origin()==CandidateUnionShadow.Origin.RULE) {
                    var single=new CandidateUnionShadow.Plan(plan.revision(),plan.inputSha256(),0,List.of(entry));
                    var request=MechanismVerificationBridge.prepareRules(input,single,library,evidenceLimit).get(0);
                    rawHash=rawHash(request.candidate()); referenceHash=request.referenceSha256();
                    preparing=false; attempts=1;
                    var response=ruleVerifier.verify(request);
                    validation=MechanismVerificationBridge.validate(request,response,input);
                } else {
                    var original=originals.get(c.sourceId()).original();
                    rawHash=rawHash(original); preparing=false; attempts=1;
                    var response=aiVerifier.verify(original);
                    validation=validateAi(original,response,input,evidenceLimit);
                }
                var observation=validation.observation();
                if(observation==null) outcomes.add(outcome(c,State.VERIFICATION_FAILED,validation.failureCode(),attempts,rawHash,referenceHash,null));
                else outcomes.add(outcome(c,State.valueOf(observation.decision().name()),null,attempts,rawHash,referenceHash,observation));
            } catch(RuntimeException ex) {
                // Do not expose exception messages containing raw/provider data; no retry or fake PASS.
                outcomes.add(outcome(c,preparing?State.PREPARATION_FAILED:State.VERIFICATION_FAILED,
                        preparing?"PREPARATION_EXCEPTION":"VERIFIER_OR_VALIDATION_EXCEPTION",attempts,rawHash,referenceHash,null));
            }
        }
        var conflicts=outcomes.stream().filter(o -> o.state()==State.PASS).map(Outcome::anchorId).distinct()
                .filter(id -> outcomes.stream().anyMatch(o -> o.anchorId().equals(id) && o.state()==State.REVIEW_REQUIRED)).sorted().toList();
        boolean incomplete=!conflicts.isEmpty() || outcomes.stream().anyMatch(o -> o.state()!=State.PASS && o.state()!=State.REVIEW_REQUIRED)
                || plan.entries().stream().anyMatch(e -> e.candidate().origin()==CandidateUnionShadow.Origin.AI
                    ? originals.get(e.candidate().sourceId()).original().contextLimited() : e.contextLimited());
        return new Result(REVISION,plan,outcomes,outcomes.stream().mapToInt(Outcome::verifierAttempts).sum(),incomplete,conflicts);
    }
    private static String rawHash(CandidateReviewEngine.Candidate c) {
        return CandidateUnionShadow.snapshotHash(new ReviewInput(c.raw()));
    }
    private static CandidateReviewEngine.Validation validateAi(CandidateReviewEngine.Candidate original,
            CandidateReviewEngine.VerificationResult response,ReviewInput input,int limit) {
        if(response==null || response.verifications()==null || response.verifications().size()!=1)
            return new CandidateReviewEngine.Validation(null,"AI_RESPONSE_CARDINALITY");
        var verification=response.verifications().get(0);
        if(verification==null || !original.candidateId().equals(verification.candidateId()))
            return new CandidateReviewEngine.Validation(null,"AI_CANDIDATE_ID");
        return SourceUnderstandingContract.validate(original,verification,input,limit,false,false,SourceUnderstandingContract.Version.ACTS_V1);
    }
    private static Outcome outcome(CandidateUnionShadow.Candidate c,State state,String failure,int attempts,
            String rawHash,String referenceHash,ReviewEvaluation.Observation observation) {
        return new Outcome(c.origin(),c.sourceId(),c.anchorId(),state,failure,attempts,rawHash,referenceHash,observation);
    }
}
