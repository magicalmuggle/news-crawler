package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.CrawlMonitor;
import com.github.magicalmuggle.CrawlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunManagerTest {
    private static final CrawlConfig CONFIG = new CrawlConfig(2, 1.0d, 100, 1, 1L, 5);

    private RunDao runDao;
    private FakeLauncher launcher;
    private RunManager manager;

    @BeforeEach
    void setUp() throws Exception {
        H2TestSupport.resetSchema();
        runDao = new RunDao(H2TestSupport.buildFactory());
        launcher = new FakeLauncher();
        manager = new RunManager(launcher, runDao, new CrawlMonitor());
    }

    @AfterEach
    void tearDown() {
        manager.close();
        runDao.close();
    }

    @Test
    void startsStopsAndPersistsTheRun() throws Exception {
        manager.start(CONFIG);
        assertTrue(launcher.started.await(5, TimeUnit.SECONDS));
        RunSnapshot running = awaitState(RunState.RUNNING);
        assertNotNull(running.getRunId());
        assertEquals(2, ((Number) running.getConfig().get("threads")).intValue());
        assertEquals(100, running.getRemainingPages());

        manager.stop();
        RunSnapshot finished = awaitState(RunState.FINISHED);
        assertEquals(StopReason.INTERRUPTED, finished.getStopReason());
        assertTrue(launcher.stopRequested);

        RunRecord record = runDao.find(finished.getRunId());
        assertNotNull(record);
        assertNotNull(record.getFinishedAt());
        assertEquals("INTERRUPTED", record.getStopReason());
    }

    @Test
    void rejectsASecondRunWhileOneIsActive() throws Exception {
        manager.start(CONFIG);
        awaitState(RunState.RUNNING);

        assertThrows(IllegalStateException.class, () -> manager.start(CONFIG));
    }

    @Test
    void rejectsStoppingWithoutARun() {
        assertThrows(IllegalStateException.class, () -> manager.stop());
    }

    @Test
    void recordsTheRunEvenWhenTheHistoryTableIsMissing() throws Exception {
        // 删掉 crawl_runs 模拟「没跑迁移」：抓取不应该被拦住
        try (Connection connection = H2TestSupport.connect();
                Statement statement = connection.createStatement()) {
            statement.execute("drop table crawl_runs");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        manager.start(new CrawlConfig(1, 1.0d, 10, 0, 1L, 3));
        RunSnapshot snapshot = awaitState(RunState.RUNNING);
        assertNull(snapshot.getRunId());
    }

    private RunSnapshot awaitState(RunState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            RunSnapshot snapshot = manager.snapshot();
            if (snapshot.getState() == expected) {
                return snapshot;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("state never became " + expected + ", current: "
                + manager.snapshot().getState());
    }

    private static final class FakeLauncher implements CrawlLauncher {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean stopRequested;

        @Override
        public CrawlExecution start(CrawlConfig config, CrawlSession session, CrawlMonitor monitor) {
            started.countDown();
            return new CrawlExecution() {
                @Override
                public void awaitCompletion() throws InterruptedException {
                    release.await();
                }

                @Override
                public void requestStop() {
                    stopRequested = true;
                    release.countDown();
                }
            };
        }
    }
}
