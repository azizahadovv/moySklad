package uz.kassa.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogThrottleTest {

    @Test
    void sameKeyIsAllowedOncePerPeriodOtherKeysAreIndependent() {
        assertTrue(LogThrottle.allow("t-a", 60_000));
        assertFalse(LogThrottle.allow("t-a", 60_000));
        assertTrue(LogThrottle.allow("t-b", 60_000));
        assertTrue(LogThrottle.allow("t-a", 0), "davr tugagach yana ruxsat");
    }
}
