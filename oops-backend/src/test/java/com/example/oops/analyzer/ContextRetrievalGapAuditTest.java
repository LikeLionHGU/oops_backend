package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.example.oops.analyzer.ContextRetrievalGapAudit.*;

class ContextRetrievalGapAuditTest {
    @Test void distinguishesAbsenceExclusionRetrievalAndSelectionWithoutInventingRankingCause() {
        var asset=ContextFitRankingPreviewTest.example("reference","검색 설명");
        var assets=List.of(asset);
        var pool=List.of(new ContextExampleSelector.Match(asset,1));
        assertThat(diagnose("missing","SPEECH",Set.of(),assets,pool,List.of())).isEqualTo(Stage.ASSET_ABSENT);
        assertThat(diagnose(asset.id(),"OCR",Set.of(),assets,pool,List.of())).isEqualTo(Stage.CHANNEL_EXCLUDED);
        assertThat(diagnose(asset.id(),"SPEECH",Set.of(asset.familyId()),assets,pool,List.of())).isEqualTo(Stage.FAMILY_EXCLUDED);
        assertThat(diagnose(asset.id(),"SPEECH",Set.of(),assets,List.of(),List.of())).isEqualTo(Stage.NOT_RETRIEVED);
        assertThat(diagnose(asset.id(),"SPEECH",Set.of(),assets,pool,List.of())).isEqualTo(Stage.RETRIEVED_NOT_SELECTED);
        assertThat(diagnose(asset.id(),"SPEECH",Set.of(),assets,pool,List.of(asset.id()))).isEqualTo(Stage.SELECTED);
    }
}
