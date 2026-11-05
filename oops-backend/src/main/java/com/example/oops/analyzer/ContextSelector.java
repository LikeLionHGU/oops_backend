package com.example.oops.analyzer;

/**
 * Experiment A context selection for one judged segment over a frozen transcript.
 * Budgets, gold labels or speaker labels are supplied at construction, never inside the model input.
 */
interface ContextSelector {
    ContextSelection select(ReviewInput.Segment target, ReviewInput transcript);
}
