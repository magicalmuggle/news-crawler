package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.CrawlMonitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 当前运行的只读快照，/api/runs/current 和 SSE 都返回它。
public final class RunSnapshot {
    private final RunState state;
    private final Long runId;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final StopReason stopReason;
    private final String error;
    private final Map<String, Object> config;
    private final int attemptedPages;
    private final int storedNews;
    private final int droppedLinks;
    private final int remainingPages;
    private final int consecutiveBlocks;
    private final List<CrawlMonitor.ThreadStatus> threads;

    public RunSnapshot(RunState state, Long runId, Instant startedAt, Instant finishedAt,
            StopReason stopReason, String error, CrawlConfig config, int attemptedPages, int storedNews,
            int droppedLinks, int remainingPages, int consecutiveBlocks,
            List<CrawlMonitor.ThreadStatus> threads) {
        this.state = state;
        this.runId = runId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.stopReason = stopReason;
        this.error = error;
        this.config = describe(config);
        this.attemptedPages = attemptedPages;
        this.storedNews = storedNews;
        this.droppedLinks = droppedLinks;
        this.remainingPages = remainingPages;
        this.consecutiveBlocks = consecutiveBlocks;
        this.threads = new ArrayList<>(threads);
    }

    public RunState getState() {
        return state;
    }

    public Long getRunId() {
        return runId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    public String getError() {
        return error;
    }

    public Map<String, Object> getConfig() {
        return config == null ? null : new LinkedHashMap<>(config);
    }

    // CrawlConfig 用的是 fluent 访问器，Jackson 发现不了 getter，这里显式转成 map
    private static Map<String, Object> describe(CrawlConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("threads", config.threads());
        map.put("requestsPerSecond", config.requestsPerSecond());
        map.put("maxPages", config.maxPages());
        map.put("maxRetries", config.maxRetries());
        map.put("retryBackoffMillis", config.retryBackoffMillis());
        map.put("circuitBreakerThreshold", config.circuitBreakerThreshold());
        return map;
    }

    public int getAttemptedPages() {
        return attemptedPages;
    }

    public int getStoredNews() {
        return storedNews;
    }

    public int getDroppedLinks() {
        return droppedLinks;
    }

    public int getRemainingPages() {
        return remainingPages;
    }

    public int getConsecutiveBlocks() {
        return consecutiveBlocks;
    }

    public List<CrawlMonitor.ThreadStatus> getThreads() {
        return new ArrayList<>(threads);
    }

    public long getElapsedMillis() {
        if (startedAt == null) {
            return 0L;
        }
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return Duration.between(startedAt, end).toMillis();
    }
}
