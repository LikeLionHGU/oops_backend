package com.example.oops.dto;

import com.example.oops.domain.RiskCategory;

/** Presentation hint derived from the final category, NOT agent identity, votes or confidence. */
public enum ReviewPerspective {
    COMMUNITY_CONTEXT, AFFECTED_PARTY, EXPRESSION_SAFETY, FACT_VERIFICATION;

    public static ReviewPerspective from(RiskCategory category) {
        if (category == null) return null;
        return switch (category) {
            case UNFAMILIAR_CONTEXT, SENSITIVE_TOPIC, TIMING_SENSITIVE -> COMMUNITY_CONTEXT;
            case BELITTLEMENT, MOCKERY, STRONG_NEGATIVE_REVIEW, GENERALIZATION,
                    DISCRIMINATION, HATE_SPEECH, SEXUAL -> AFFECTED_PARTY;
            case PRIVACY, PROFANITY, VIOLENCE, GRAPHIC_METAPHOR -> EXPRESSION_SAFETY;
            case FACT_ERROR, MISINFORMATION, UNVERIFIED_CLAIM -> FACT_VERIFICATION;
            default -> null;
        };
    }
}
