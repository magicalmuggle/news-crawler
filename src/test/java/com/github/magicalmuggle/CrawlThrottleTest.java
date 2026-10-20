package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlThrottleTest {

    @Test
    void disabledThrottleDoesNotSleep() {
        CrawlThrottle throttle = CrawlThrottle.disabled();
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            throttle.acquire();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 100L, "disabled throttle should not sleep, took " + elapsedMillis + "ms");
    }

    @Test
    void acquireEnforcesTheRequestRate() {
        CrawlThrottle throttle = new CrawlThrottle(50.0d, 0.0d);
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            throttle.acquire();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= 70L, "five acquires at 20ms should take about 80ms, took " + elapsedMillis);
        assertTrue(elapsedMillis < 2_000L, "throttle should not block far longer than requested");
    }

    @Test
    void jitteredIntervalStaysWithinBounds() {
        double jitter = 0.4d;
        CrawlThrottle throttle = new CrawlThrottle(2.0d, jitter);
        long base = TimeUnit.SECONDS.toNanos(1) / 2L;
        long lowerBound = (long) (base * (1.0d - jitter)) - 1L;
        long upperBound = (long) (base * (1.0d + jitter)) + 1L;
        for (int i = 0; i < 500; i++) {
            long interval = throttle.nextIntervalNanos();
            assertTrue(interval >= lowerBound && interval <= upperBound,
                    "interval " + interval + " outside [" + lowerBound + ", " + upperBound + "]");
        }
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlThrottle(0.0d));
    }
}
