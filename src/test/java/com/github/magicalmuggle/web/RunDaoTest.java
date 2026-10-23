package com.github.magicalmuggle.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunDaoTest {
    private RunDao dao;

    @BeforeEach
    void setUp() throws Exception {
        H2TestSupport.resetSchema();
        dao = new RunDao(H2TestSupport.buildFactory());
    }

    @AfterEach
    void tearDown() {
        dao.close();
    }

    @Test
    void storesAndFinishesARun() {
        RunRecord record = new RunRecord();
        record.setStartedAt(Instant.parse("2026-10-20T10:00:00Z"));
        record.setThreads(2);
        record.setRequestsPerSecond(0.5d);
        record.setMaxPages(100);
        record.setMaxRetries(2);
        record.setRetryBackoffMillis(1000L);
        record.setCircuitBreakerThreshold(5);
        record.setRemainingPages(100);

        dao.insert(record);

        assertNotNull(record.getId());
        RunRecord stored = dao.find(record.getId());
        assertEquals(2, stored.getThreads());
        assertEquals(0.5d, stored.getRequestsPerSecond());
        assertEquals(100, stored.getMaxPages());
        assertNull(stored.getFinishedAt());
        assertNull(stored.getStopReason());

        record.setFinishedAt(Instant.parse("2026-10-20T10:05:00Z"));
        record.setAttemptedPages(42);
        record.setNewsStored(7);
        record.setDroppedLinks(3);
        record.setRemainingPages(58);
        record.setConsecutiveBlocks(1);
        record.setStopReason(StopReason.INTERRUPTED.name());
        dao.finish(record);

        RunRecord finished = dao.find(record.getId());
        assertEquals(42, finished.getAttemptedPages());
        assertEquals(7, finished.getNewsStored());
        assertEquals(3, finished.getDroppedLinks());
        assertEquals("INTERRUPTED", finished.getStopReason());
        assertNotNull(finished.getFinishedAt());
        assertEquals(1, dao.recent(10).size());
    }

    @Test
    void returnsNullForAnUnknownRun() {
        assertNull(dao.find(999999L));
    }
}
