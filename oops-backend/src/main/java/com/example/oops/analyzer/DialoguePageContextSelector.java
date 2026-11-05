package com.example.oops.analyzer;

import java.util.List;

/**
 * A0-T: the context the production reading page actually advertises for the target,
 * i.e. the page window (prefix context plus every page primary) minus the target.
 * No window means production gives the target no admissible context, so neither does A0-T.
 */
final class DialoguePageContextSelector implements ContextSelector {
    static final String ID = "A0-T:" + DialogueReadingPage.REVISION;

    @Override
    public ContextSelection select(ReviewInput.Segment target, ReviewInput transcript) {
        var page = DialogueReadingPage.plan(transcript).stream()
                .filter(p -> p.primary().stream().anyMatch(s -> s.id().equals(target.id())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target is not a planned primary: " + target.id()));
        List<String> ids = DialogueReadingPage.windows(page).stream()
                .filter(u -> u.anchorId().equals(target.id()))
                .findFirst()
                .map(ReviewUnit::segmentIds)
                .orElse(List.of());
        return new ContextSelection(ids.stream().filter(id -> !id.equals(target.id())).toList(), ID);
    }
}
