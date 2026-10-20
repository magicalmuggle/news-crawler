package com.github.magicalmuggle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 记录连续的"被拦"响应（403 / 429 / 503）。达到阈值就熔断，让所有线程停止抓取。
// 一次正常响应会把计数清零，所以偶发的 403 不会误伤。
public final class CircuitBreaker {
    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);

    private final int threshold;
    private int consecutiveBlocks;
    private boolean tripped;

    public CircuitBreaker(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be at least 1, got: " + threshold);
        }
        this.threshold = threshold;
    }

    public synchronized void recordBlock() {
        consecutiveBlocks++;
        if (!tripped && consecutiveBlocks >= threshold) {
            tripped = true;
            LOG.error("circuit breaker tripped after {} consecutive blocked responses, stopping the crawl",
                    consecutiveBlocks);
        }
    }

    public synchronized void recordSuccess() {
        consecutiveBlocks = 0;
    }

    public synchronized boolean isTripped() {
        return tripped;
    }

    public synchronized int consecutiveBlocks() {
        return consecutiveBlocks;
    }
}
