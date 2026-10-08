package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;

/** Keeps ordinary strong negative reviews visible without assigning them medium/high severity. */
final class ReviewScorePolicy {

    private static final double STRONG_NEGATIVE_REVIEW_MAX_SCORE = 0.39;

    private ReviewScorePolicy() {}

    static double cap(RiskCategory category, double score) {
        // A figurative expression is not evidence of a real assault; avoid HIGH escalation.
        if (category == RiskCategory.GRAPHIC_METAPHOR) return Math.min(score, 0.59);
        return category == RiskCategory.STRONG_NEGATIVE_REVIEW
                ? Math.min(score, STRONG_NEGATIVE_REVIEW_MAX_SCORE)
                : score;
    }
}
