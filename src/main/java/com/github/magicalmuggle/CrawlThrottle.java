package com.github.magicalmuggle;

import java.util.Random;
import java.util.concurrent.TimeUnit;

// 所有线程共享的全局速率闸门：每次请求前调用 acquire()。
// 只有拿到"槽位"的线程才会真正发请求，因此整个进程的请求速率不会超过 requestsPerSecond。
// 间隔带随机抖动，避免固定周期这种明显的机器特征。
public final class CrawlThrottle {
    private static final double DEFAULT_JITTER = 0.4d;

    private final long baseIntervalNanos;
    private final double jitter;
    private final Random random = new Random();
    private long nextAllowedNanos;

    public CrawlThrottle(double requestsPerSecond) {
        this(requestsPerSecond, DEFAULT_JITTER);
    }

    public CrawlThrottle(double requestsPerSecond, double jitter) {
        if (!(requestsPerSecond > 0.0d)) {
            throw new IllegalArgumentException("requestsPerSecond must be positive, got: " + requestsPerSecond);
        }
        if (jitter < 0.0d || jitter >= 1.0d) {
            throw new IllegalArgumentException("jitter must be in [0, 1), got: " + jitter);
        }
        this.baseIntervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / requestsPerSecond);
        this.jitter = jitter;
    }

    private CrawlThrottle(long baseIntervalNanos, double jitter) {
        this.baseIntervalNanos = baseIntervalNanos;
        this.jitter = jitter;
    }

    // 测试和"完全不想限速"的场景使用；生产入口不要用它。
    public static CrawlThrottle disabled() {
        return new CrawlThrottle(0L, 0.0d);
    }

    public void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            waitNanos = nextAllowedNanos - now;
            nextAllowedNanos = Math.max(now, nextAllowedNanos) + jitteredIntervalNanos();
        }
        if (waitNanos > 0L) {
            sleepNanos(waitNanos);
        }
    }

    // 仅用于测试：观察抖动后的单次间隔。
    long nextIntervalNanos() {
        synchronized (this) {
            return jitteredIntervalNanos();
        }
    }

    private long jitteredIntervalNanos() {
        if (baseIntervalNanos <= 0L) {
            return 0L;
        }
        double factor = 1.0d - jitter + (2.0d * jitter * random.nextDouble());
        return (long) (baseIntervalNanos * factor);
    }

    private static void sleepNanos(long nanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        int restNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
        try {
            Thread.sleep(millis, restNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
