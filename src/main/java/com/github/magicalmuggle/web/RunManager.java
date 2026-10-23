package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.CrawlMonitor;
import com.github.magicalmuggle.CrawlSession;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// 单进程内的运行管理器：同一时刻只允许一次抓取，负责状态迁移、停止和运行历史。
// 不负责决定抓什么，具体流程交给 CrawlLauncher。
public final class RunManager implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(RunManager.class);

    private final Object lock = new Object();
    private final CrawlLauncher launcher;
    private final RunDao runDao;
    private final CrawlMonitor monitor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crawl-run");
        thread.setDaemon(true);
        return thread;
    });

    private RunState state = RunState.IDLE;
    private StopReason stopReason;
    private Long runId;
    private CrawlConfig config;
    private Instant startedAt;
    private Instant finishedAt;
    private String error;
    private boolean stopRequested;
    private CrawlSession session;
    private CrawlExecution execution;

    private int attemptedPages;
    private int storedNews;
    private int droppedLinks;
    private int remainingPages;
    private int consecutiveBlocks;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public RunManager(CrawlLauncher launcher, RunDao runDao, CrawlMonitor monitor) {
        this.launcher = launcher;
        this.runDao = runDao;
        this.monitor = monitor;
    }

    public void start(CrawlConfig config) {
        synchronized (lock) {
            if (state.isActive()) {
                throw new IllegalStateException("a crawl run is already in progress");
            }
            this.config = config;
            this.state = RunState.STARTING;
            this.startedAt = Instant.now();
            this.finishedAt = null;
            this.stopReason = null;
            this.error = null;
            this.stopRequested = false;
            this.runId = null;
            this.attemptedPages = 0;
            this.storedNews = 0;
            this.droppedLinks = 0;
            this.remainingPages = config.maxPages();
            this.consecutiveBlocks = 0;
            recordStart(config);
            executor.submit(this::runOnce);
        }
    }

    public void stop() {
        CrawlSession currentSession;
        CrawlExecution currentExecution;
        synchronized (lock) {
            if (state == RunState.STARTING) {
                throw new IllegalStateException("the run is still starting, try again in a moment");
            }
            if (state != RunState.STOPPING && state != RunState.RUNNING) {
                throw new IllegalStateException("no crawl run is in progress");
            }
            stopRequested = true;
            state = RunState.STOPPING;
            currentSession = session;
            currentExecution = execution;
        }
        if (currentSession != null) {
            currentSession.requestStop();
        }
        if (currentExecution != null) {
            currentExecution.requestStop();
        }
    }

    public RunSnapshot snapshot() {
        synchronized (lock) {
            CrawlSession current = session;
            int attempted = current != null ? current.attemptedPages() : attemptedPages;
            int stored = current != null ? current.storedNews() : storedNews;
            int dropped = current != null ? current.droppedLinks() : droppedLinks;
            int remaining = current != null ? current.remainingPages() : remainingPages;
            int blocks = current != null ? current.consecutiveBlocks() : consecutiveBlocks;
            return new RunSnapshot(state, runId, startedAt, finishedAt, stopReason, error, config,
                    attempted, stored, dropped, remaining, blocks, monitor.snapshot());
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return state.isActive();
        }
    }

    public List<RunRecord> history(int limit) {
        return runDao.recent(limit);
    }

    public RunRecord historyEntry(long id) {
        return runDao.find(id);
    }

    @Override
    public void close() {
        CrawlSession currentSession;
        CrawlExecution currentExecution;
        synchronized (lock) {
            currentSession = session;
            currentExecution = execution;
        }
        if (currentSession != null) {
            currentSession.requestStop();
        }
        if (currentExecution != null) {
            currentExecution.requestStop();
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runOnce() {
        CrawlConfig runConfig;
        synchronized (lock) {
            runConfig = config;
        }
        CrawlSession runSession = new CrawlSession(runConfig);
        synchronized (lock) {
            session = runSession;
            state = RunState.RUNNING;
        }
        try {
            CrawlExecution runExecution = launcher.start(runConfig, runSession, monitor);
            synchronized (lock) {
                execution = runExecution;
                if (stopRequested) {
                    runSession.requestStop();
                    runExecution.requestStop();
                }
            }
            runExecution.awaitCompletion();
            synchronized (lock) {
                stopReason = resolveStopReason(runSession);
                state = RunState.FINISHED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                stopReason = StopReason.INTERRUPTED;
                state = RunState.FINISHED;
            }
        } catch (Throwable throwable) {
            LOG.error("crawl run failed", throwable);
            synchronized (lock) {
                error = rootMessage(throwable);
                stopReason = StopReason.FAILED;
                state = RunState.FAILED;
            }
        } finally {
            finishRun(runSession);
        }
    }

    private StopReason resolveStopReason(CrawlSession runSession) {
        synchronized (lock) {
            if (stopRequested) {
                return StopReason.INTERRUPTED;
            }
        }
        if (runSession.isTripped()) {
            return StopReason.CIRCUIT_TRIPPED;
        }
        if (runSession.remainingPages() <= 0) {
            return StopReason.BUDGET_EXHAUSTED;
        }
        return StopReason.COMPLETED;
    }

    private void finishRun(CrawlSession runSession) {
        RunRecord record;
        try {
            runSession.close();
        } catch (IOException e) {
            LOG.warn("failed to close the http client after the run: {}", e.getMessage());
        }
        synchronized (lock) {
            finishedAt = Instant.now();
            attemptedPages = runSession.attemptedPages();
            storedNews = runSession.storedNews();
            droppedLinks = runSession.droppedLinks();
            remainingPages = runSession.remainingPages();
            consecutiveBlocks = runSession.consecutiveBlocks();
            session = null;
            execution = null;
            record = buildFinishRecord();
        }
        if (record == null) {
            return;
        }
        try {
            runDao.finish(record);
        } catch (RuntimeException e) {
            LOG.warn("failed to persist the run result (did you run mvn flyway:migrate?): {}", e.getMessage());
        }
        LOG.info("crawl run {} finished: reason={}, attemptedPages={}, newsStored={}, droppedLinks={}",
                record.getId(), record.getStopReason(), record.getAttemptedPages(),
                record.getNewsStored(), record.getDroppedLinks());
    }

    private RunRecord buildFinishRecord() {
        if (runId == null) {
            return null;
        }
        RunRecord record = new RunRecord();
        record.setId(runId);
        record.setFinishedAt(finishedAt);
        record.setAttemptedPages(attemptedPages);
        record.setNewsStored(storedNews);
        record.setDroppedLinks(droppedLinks);
        record.setRemainingPages(remainingPages);
        record.setConsecutiveBlocks(consecutiveBlocks);
        record.setStopReason(stopReason == null ? null : stopReason.name());
        return record;
    }

    private void recordStart(CrawlConfig runConfig) {
        RunRecord record = new RunRecord();
        record.setStartedAt(startedAt);
        record.setThreads(runConfig.threads());
        record.setRequestsPerSecond(runConfig.requestsPerSecond());
        record.setMaxPages(runConfig.maxPages());
        record.setMaxRetries(runConfig.maxRetries());
        record.setRetryBackoffMillis(runConfig.retryBackoffMillis());
        record.setCircuitBreakerThreshold(runConfig.circuitBreakerThreshold());
        record.setAttemptedPages(0);
        record.setNewsStored(0);
        record.setDroppedLinks(0);
        record.setRemainingPages(runConfig.maxPages());
        record.setConsecutiveBlocks(0);
        try {
            runDao.insert(record);
            runId = record.getId();
        } catch (RuntimeException e) {
            // 没跑迁移时不应该拦住抓取：历史记不了，但这次运行照常进行
            LOG.warn("failed to record the crawl run (did you run mvn flyway:migrate?): {}", e.getMessage());
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }
}
