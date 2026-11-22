package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;

/** Test shorthand for the production entry point with no optional services. */
final class CandidateRuns {
    static final CandidateReviewEngine.RunServices NO_SERVICES =
            new CandidateReviewEngine.RunServices(null, null, null);
    private CandidateRuns() {}

    static CandidateReviewEngine.RunOptions options(int maxCandidates) {
        return new CandidateReviewEngine.RunOptions(maxCandidates, 8, false,
                new CandidateReviewEngine.VerificationFeatures(false, false, false), false);
    }

    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates) {
        return CandidateReviewEngine.run(client, context, NO_SERVICES, options(maxCandidates));
    }
}
