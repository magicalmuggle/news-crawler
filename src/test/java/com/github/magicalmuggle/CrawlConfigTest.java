package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrawlConfigTest {

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(0, 1.0d, 1, 0, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, 0.0d, 1, 0, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, Double.NaN, 1, 0, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, 1.0d, 0, 0, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, 1.0d, 1, -1, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, 1.0d, 1, 0, -1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrawlConfig(1, 1.0d, 1, 0, 0L, 0));
    }

    @Test
    void keepsTheConfiguredValues() {
        CrawlConfig config = new CrawlConfig(2, 0.5d, 100, 1, 250L, 5);
        assertEquals(2, config.threads());
        assertEquals(0.5d, config.requestsPerSecond());
        assertEquals(100, config.maxPages());
        assertEquals(1, config.maxRetries());
        assertEquals(250L, config.retryBackoffMillis());
        assertEquals(5, config.circuitBreakerThreshold());
    }
}
