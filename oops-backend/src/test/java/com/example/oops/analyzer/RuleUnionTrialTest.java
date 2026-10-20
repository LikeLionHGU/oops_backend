package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleUnionTrialTest {
    @Test void wrongPlanOrConsentRejectedBeforeCredentials() {
        assertThrows(IllegalArgumentException.class,() -> RuleUnionTrial.authorize(new byte[0],"wrong",RuleUnionTrial.CONSENT));
        assertThrows(IllegalArgumentException.class,() -> RuleUnionTrial.authorize(new byte[0],RuleUnionTrial.SHA,"old-consent"));
    }
    @Test void httpAttemptLimitIncludesRetries() {
        var b=new RuleUnionTrial.Budget();
        for(int i=0;i<80;i++) b.reserve(100);
        assertThrows(IllegalArgumentException.class,() -> b.reserve(100));
        assertEquals(80,b.attempts);assertTrue(b.exhausted);assertTrue(b.reserved<=.50);
    }
    @Test void dollarReservationStopsBeforeCall() {
        var b=new RuleUnionTrial.Budget();
        assertThrows(IllegalArgumentException.class,() -> b.reserve(6_000_000));
        assertEquals(0,b.attempts);assertEquals(0,b.reserved);assertTrue(b.exhausted);
    }
}
