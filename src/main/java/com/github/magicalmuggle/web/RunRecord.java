package com.github.magicalmuggle.web;

import java.time.Instant;

// crawl_runs 表的一行：一次运行的参数快照与结果。
public class RunRecord {
    private Long id;
    private Instant startedAt;
    private Instant finishedAt;
    private int threads;
    private double requestsPerSecond;
    private int maxPages;
    private int maxRetries;
    private long retryBackoffMillis;
    private int circuitBreakerThreshold;
    private int attemptedPages;
    private int newsStored;
    private int droppedLinks;
    private int remainingPages;
    private int consecutiveBlocks;
    private String stopReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }

    public void setRequestsPerSecond(double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public int getAttemptedPages() {
        return attemptedPages;
    }

    public void setAttemptedPages(int attemptedPages) {
        this.attemptedPages = attemptedPages;
    }

    public int getNewsStored() {
        return newsStored;
    }

    public void setNewsStored(int newsStored) {
        this.newsStored = newsStored;
    }

    public int getDroppedLinks() {
        return droppedLinks;
    }

    public void setDroppedLinks(int droppedLinks) {
        this.droppedLinks = droppedLinks;
    }

    public int getRemainingPages() {
        return remainingPages;
    }

    public void setRemainingPages(int remainingPages) {
        this.remainingPages = remainingPages;
    }

    public int getConsecutiveBlocks() {
        return consecutiveBlocks;
    }

    public void setConsecutiveBlocks(int consecutiveBlocks) {
        this.consecutiveBlocks = consecutiveBlocks;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }
}
