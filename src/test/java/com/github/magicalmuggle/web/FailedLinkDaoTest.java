package com.github.magicalmuggle.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedLinkDaoTest {
    private FailedLinkDao dao;

    @BeforeEach
    void setUp() throws Exception {
        H2TestSupport.resetSchema();
        dao = new FailedLinkDao(H2TestSupport.buildFactory());
    }

    @AfterEach
    void tearDown() {
        dao.close();
    }

    @Test
    void movesAFailedLinkBackToTheQueue() throws Exception {
        H2TestSupport.insertFailedLink("https://sina.cn/broken", 429, "RATE_LIMITED");

        assertEquals(1L, dao.count());
        List<FailedLink> failed = dao.recent(10);
        assertEquals(1, failed.size());
        assertEquals(Integer.valueOf(429), failed.get(0).getStatusCode());
        assertEquals("RATE_LIMITED", failed.get(0).getOutcome());

        assertTrue(dao.requeue(failed.get(0).getId()));

        assertEquals(0L, dao.count());
        assertEquals(1, H2TestSupport.countRows("links_to_be_processed"));
    }

    @Test
    void returnsFalseForAnUnknownFailedLink() {
        assertFalse(dao.requeue(999999L));
    }
}
