package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class LongVideoRoutingRetryTest {
    @TempDir Path temp;
    @Test void refusesEditedPriorOrFreshBudgetInsteadOfCarryForward() {
        assertThatThrownBy(() -> LongVideoRoutingRetry.carryBudget(temp,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("preserved prior");
    }
    @Test void cumulativeBudgetIncludesEarlierFailedAttemptReservations() throws Exception {
        var budget=new LongVideoRoutingTrial.Budget(temp);
        budget.reserved=.5972385000000002; budget.attempts=97;
        budget.reserve(1024);
        assertThat(budget.attempts).isEqualTo(98);
        assertThat(budget.reserved).isGreaterThan(.5972385000000002);
        budget.attempts=400;
        assertThatThrownBy(() -> budget.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.attempts).isEqualTo(400);
    }
}
