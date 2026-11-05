package com.example.oops.analyzer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;

/**
 * Experiment-only context budget B: tokens of the rendered CONTEXT_ONLY rows and their markers.
 * A fixed substitute counter, not the serving model's tokenizer; total API usage is measured separately.
 */
final class ContextTokenBudget {
    static final String COUNTER_ID = "jtokkit-1.1.0:o200k_base";
    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    record Measurement(int contextTokens, int budget, boolean exceeded) {}

    private final int budget;

    ContextTokenBudget(int budget) {
        if (budget < 1) throw new IllegalArgumentException("Context token budget must be positive");
        this.budget = budget;
    }

    int budget() {
        return budget;
    }

    Measurement measure(ExperimentalBatchRendering rendering, List<ReviewInput.Segment> raw) {
        int tokens = rendering.contextRows(raw).isEmpty() ? 0 : count(rendering.budgetedContext(raw));
        return new Measurement(tokens, budget, tokens > budget);
    }

    static int count(String text) {
        return ENCODING.countTokensOrdinary(text);
    }
}
