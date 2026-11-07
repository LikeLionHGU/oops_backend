package com.example.oops.analyzer;

import java.util.List;

/** T0: the judged segment alone, with no context. */
final class TargetOnlyContextSelector implements ContextSelector {
    static final String ID = "T0:target-only-v1";

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        if (transcript.find(target.id()).isEmpty()) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        return new ContextSelection(List.of(), ID);
    }
}
