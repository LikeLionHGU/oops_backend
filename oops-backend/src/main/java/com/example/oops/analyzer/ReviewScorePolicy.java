package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;

/** Keeps ordinary strong negative reviews visible without assigning them medium/high severity. */
final class ReviewScorePolicy {

    private static final double STRONG_NEGATIVE_REVIEW_MAX_SCORE = 0.39;

    private ReviewScorePolicy() {}

    static double cap(RiskCategory category, double score) {
        return category == RiskCategory.STRONG_NEGATIVE_REVIEW
                ? Math.min(score, STRONG_NEGATIVE_REVIEW_MAX_SCORE)
                : score;
    }
}
