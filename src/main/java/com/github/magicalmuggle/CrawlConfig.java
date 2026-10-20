package com.github.magicalmuggle;

import java.util.Properties;

// 爬取行为的配置，优先级从高到低：
// 1. JVM 系统属性，例如 -Dcrawler.ratePerSecond=0.5
// 2. 环境变量，例如 NEWS_CRAWLER_RATE_PER_SECOND=0.5
// 3. 工作目录下的 local.properties
// 4. 内置默认值
// 默认值是给单个站点练手用的保守值：3 线程、1 req/s、单次最多 500 页。
public final class CrawlConfig {
    public static final String THREADS_KEY = "crawler.threads";
    public static final String REQUESTS_PER_SECOND_KEY = "crawler.ratePerSecond";
    public static final String MAX_PAGES_KEY = "crawler.maxPages";
    public static final String MAX_RETRIES_KEY = "crawler.maxRetries";
    public static final String RETRY_BACKOFF_MILLIS_KEY = "crawler.retryBackoffMillis";
    public static final String CIRCUIT_BREAKER_THRESHOLD_KEY = "crawler.circuitBreakerThreshold";

    private static final int DEFAULT_THREADS = 3;
    private static final double DEFAULT_REQUESTS_PER_SECOND = 1.0d;
    private static final int DEFAULT_MAX_PAGES = 500;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_BACKOFF_MILLIS = 5000L;
    private static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 10;

    private final int threads;
    private final double requestsPerSecond;
    private final int maxPages;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final int circuitBreakerThreshold;

    public CrawlConfig(int threads, double requestsPerSecond, int maxPages, int maxRetries,
            long retryBackoffMillis, int circuitBreakerThreshold) {
        if (threads < 1) {
            throw new IllegalArgumentException(THREADS_KEY + " must be at least 1, got: " + threads);
        }
        if (!(requestsPerSecond > 0.0d)) {
            throw new IllegalArgumentException(REQUESTS_PER_SECOND_KEY + " must be positive, got: "
                    + requestsPerSecond);
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException(MAX_PAGES_KEY + " must be at least 1, got: " + maxPages);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException(MAX_RETRIES_KEY + " must not be negative, got: " + maxRetries);
        }
        if (retryBackoffMillis < 0L) {
            throw new IllegalArgumentException(RETRY_BACKOFF_MILLIS_KEY + " must not be negative, got: "
                    + retryBackoffMillis);
        }
        if (circuitBreakerThreshold < 1) {
            throw new IllegalArgumentException(CIRCUIT_BREAKER_THRESHOLD_KEY + " must be at least 1, got: "
                    + circuitBreakerThreshold);
        }
        this.threads = threads;
        this.requestsPerSecond = requestsPerSecond;
        this.maxPages = maxPages;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public static CrawlConfig load() {
        Properties properties = LocalConfig.loadLocalProperties();
        return new CrawlConfig(
                readInt(properties, THREADS_KEY, "NEWS_CRAWLER_THREADS", DEFAULT_THREADS),
                readDouble(properties, REQUESTS_PER_SECOND_KEY, "NEWS_CRAWLER_RATE_PER_SECOND",
                        DEFAULT_REQUESTS_PER_SECOND),
                readInt(properties, MAX_PAGES_KEY, "NEWS_CRAWLER_MAX_PAGES", DEFAULT_MAX_PAGES),
                readInt(properties, MAX_RETRIES_KEY, "NEWS_CRAWLER_MAX_RETRIES", DEFAULT_MAX_RETRIES),
                readLong(properties, RETRY_BACKOFF_MILLIS_KEY, "NEWS_CRAWLER_RETRY_BACKOFF_MILLIS",
                        DEFAULT_RETRY_BACKOFF_MILLIS),
                readInt(properties, CIRCUIT_BREAKER_THRESHOLD_KEY, "NEWS_CRAWLER_CIRCUIT_BREAKER_THRESHOLD",
                        DEFAULT_CIRCUIT_BREAKER_THRESHOLD));
    }

    private static int readInt(Properties properties, String key, String environmentVariable, int defaultValue) {
        String raw = read(properties, key, environmentVariable);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + raw, e);
        }
    }

    private static long readLong(Properties properties, String key, String environmentVariable, long defaultValue) {
        String raw = read(properties, key, environmentVariable);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + raw, e);
        }
    }

    private static double readDouble(Properties properties, String key, String environmentVariable,
            double defaultValue) {
        String raw = read(properties, key, environmentVariable);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got: " + raw, e);
        }
    }

    private static String read(Properties properties, String key, String environmentVariable) {
        String value = System.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getenv(environmentVariable);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = properties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return null;
    }

    public int threads() {
        return threads;
    }

    public double requestsPerSecond() {
        return requestsPerSecond;
    }

    public int maxPages() {
        return maxPages;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public int circuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    @Override
    public String toString() {
        return "threads=" + threads
                + ", ratePerSecond=" + requestsPerSecond
                + ", maxPages=" + maxPages
                + ", maxRetries=" + maxRetries
                + ", retryBackoffMillis=" + retryBackoffMillis
                + ", circuitBreakerThreshold=" + circuitBreakerThreshold;
    }
}
