package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FixedBoundaryBaselineTrialTest {
    @Test void httpLimitStopsTrialRatherThanContinuingToNextCase() {
        for(boolean global:new boolean[]{false,true}) {
            var guard=new ConfrontationStageTrial.Guard();
            if(global)guard.attempts=FixedBoundaryBaselineTrial.LIMIT;
            else guard.caseAttempts=FixedBoundaryBaselineTrial.PER_CASE;
            assertThatThrownBy(()->FixedBoundaryBaselineTrial.enforceLimits(guard)).isInstanceOf(IllegalStateException.class);
            assertThat(guard.blocked).isTrue();
        }
    }
}
