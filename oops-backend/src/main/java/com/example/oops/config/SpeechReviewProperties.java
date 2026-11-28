package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Existing analysis keys; only speech-review wiring is owned here. */
@ConfigurationProperties(prefix = "oops.analysis")
public record SpeechReviewProperties(
        // Migration guard only: false must fail instead of silently changing an old deployment's algorithm.
        @DefaultValue("true") boolean candidateReviewEnabled,
        @DefaultValue("24") int candidateReviewMaxCandidates,
        @DefaultValue("0") int candidateReviewEvidenceLimit,
        @DefaultValue("false") boolean candidateSourceRelationsEnabled,
        @DefaultValue("false") boolean finalReviewBasisEnabled,
        @DefaultValue("false") boolean situationFirstVerificationEnabled,
        @DefaultValue("false") boolean sourceUnderstandingEnabled,
        @DefaultValue("acts-v1") String sourceUnderstandingContract,
        @DefaultValue("false") boolean ruleCandidateUnionEnabled) {
    public SpeechReviewProperties {
        if (!candidateReviewEnabled)
            throw new IllegalArgumentException("candidate-review-enabled=false is no longer supported; legacy speech review was removed");
        if (candidateReviewMaxCandidates < 1 || candidateReviewMaxCandidates > 200)
            throw new IllegalArgumentException("Candidate budget must be 1..200");
        if (candidateReviewEvidenceLimit < 0)
            throw new IllegalArgumentException("Evidence limit must be nonnegative");
    }
}
