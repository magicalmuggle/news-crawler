package com.github.magicalmuggle;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// 一次抓取运行的共享状态：限速、熔断、页数预算和统计。
// 所有 Crawler 线程共用一个实例，这样"全局限速"和"全局熔断"才是全局的。
public final class CrawlSession implements Closeable {
    private final CrawlThrottle throttle;
    private final CircuitBreaker circuitBreaker;
    private final HttpFetcher fetcher;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final AtomicInteger remainingPages;
    private final AtomicInteger attemptedPages = new AtomicInteger();
    private final AtomicInteger storedNews = new AtomicInteger();
    private final AtomicInteger droppedLinks = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public CrawlSession(CrawlConfig config) {
        this.throttle = new CrawlThrottle(config.requestsPerSecond());
        this.circuitBreaker = new CircuitBreaker(config.circuitBreakerThreshold());
        this.fetcher = new HttpFetcher(Math.max(4, config.threads()));
        this.maxRetries = config.maxRetries();
        this.retryBackoffMillis = config.retryBackoffMillis();
        this.remainingPages = new AtomicInteger(config.maxPages());
    }

    // 熔断、页数预算用完或被要求停止时返回 false，所有线程据此退出循环。
    public boolean shouldContinue() {
        return !stopped.get() && !circuitBreaker.isTripped() && remainingPages.get() > 0;
    }

    // 供 Web 界面的「停止」使用。只置标志，不打断正在进行的请求；
    // 要立刻唤醒退避中的线程，调用方还需要 interrupt 对应线程。
    public void requestStop() {
        stopped.set(true);
    }

    public boolean isStopped() {
        return stopped.get();
    }

    // 成功领取一次"页面额度"才返回 true；失败（被拦、超时）不消耗额度。
    public boolean tryConsumePage() {
        while (true) {
            int remaining = remainingPages.get();
            if (remaining <= 0) {
                return false;
            }
            if (remainingPages.compareAndSet(remaining, remaining - 1)) {
                attemptedPages.incrementAndGet();
                return true;
            }
        }
    }

    public void acquireSlot() {
        throttle.acquire();
    }

    public FetchResult fetch(String url) throws IOException {
        return fetcher.fetch(url);
    }

    public void recordBlockedResponse() {
        circuitBreaker.recordBlock();
    }

    public void recordSuccessfulResponse() {
        circuitBreaker.recordSuccess();
    }

    public boolean isTripped() {
        return circuitBreaker.isTripped();
    }

    public int consecutiveBlocks() {
        return circuitBreaker.consecutiveBlocks();
    }

    public void recordNewsStored() {
        storedNews.incrementAndGet();
    }

    public void recordLinkDropped() {
        droppedLinks.incrementAndGet();
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public int attemptedPages() {
        return attemptedPages.get();
    }

    public int storedNews() {
        return storedNews.get();
    }

    public int droppedLinks() {
        return droppedLinks.get();
    }

    public int remainingPages() {
        return remainingPages.get();
    }

    @Override
    public void close() throws IOException {
        fetcher.close();
    }
}
