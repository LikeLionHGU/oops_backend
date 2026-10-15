package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContextSemanticRankExecutionTest {
    @Test void unauthorizedOrDifferentPlanNeverRuns() {
        byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ContextSemanticRankExecution.authorized(bytes,ReferenceOnlyTrial.sha(bytes),"old-consent"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextSemanticRankExecution.authorized(bytes,"wrong",ContextSemanticRankExecution.CONSENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void tiesAndNormalsDoNotInflateStrictFirstMetric() {
        var tie=Map.<String,Object>of("queryKind","SOURCE","strictFirst",false,"topIncludesTarget",true);
        var normal=Map.<String,Object>of("queryKind","NORMAL_CONTROL","strictFirst",false,"topIncludesTarget",false);
        assertThat(ContextSemanticRankExecution.metrics(List.of(tie,normal)))
                .containsEntry("sourceRequests",1).containsEntry("sourceStrictFirst",0L)
                .containsEntry("sourceTopGroupIncludesTarget",1L);
    }
}
