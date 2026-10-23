package com.example.oops.analyzer;

import java.util.*;

/** Offline diagnosis of a declared retrieval target, never a content verdict. */
final class ContextRetrievalGapAudit {
    enum Stage { ASSET_ABSENT, CHANNEL_EXCLUDED, FAMILY_EXCLUDED,
        NOT_RETRIEVED, RETRIEVED_NOT_SELECTED, SELECTED }
    static Stage diagnose(String expectedId, String channel, Set<String> excludedFamilies,
            List<ReviewGuidelineLibrary.ContextExample> assets,
            List<ContextExampleSelector.Match> pool, List<String> selectedIds) {
        var asset=assets.stream().filter(e->e.id().equals(expectedId)).findFirst();
        if(asset.isEmpty()) return Stage.ASSET_ABSENT;
        if(!asset.get().channels().contains(channel)) return Stage.CHANNEL_EXCLUDED;
        if(excludedFamilies.contains(asset.get().familyId())) return Stage.FAMILY_EXCLUDED;
        if(pool.stream().noneMatch(m->m.example().id().equals(expectedId))) return Stage.NOT_RETRIEVED;
        return selectedIds.contains(expectedId) ? Stage.SELECTED : Stage.RETRIEVED_NOT_SELECTED;
    }
}
