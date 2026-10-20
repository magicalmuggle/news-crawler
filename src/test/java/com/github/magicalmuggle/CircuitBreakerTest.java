package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void tripsAfterThresholdConsecutiveBlocks() {
        CircuitBreaker breaker = new CircuitBreaker(2);
        breaker.recordBlock();
        assertFalse(breaker.isTripped());
        breaker.recordBlock();
        assertTrue(breaker.isTripped());
        assertEquals(2, breaker.consecutiveBlocks());
    }

    @Test
    void successResetsTheConsecutiveCounter() {
        CircuitBreaker breaker = new CircuitBreaker(2);
        breaker.recordBlock();
        breaker.recordSuccess();
        breaker.recordBlock();
        assertFalse(breaker.isTripped());
        assertEquals(1, breaker.consecutiveBlocks());
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0));
    }
}
