package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 把「参数当前生效值 + 来源」整理成表格数据，来源优先级与 CrawlConfig#resolve 保持一致。
public final class ConfigReport {
    private ConfigReport() {
    }

    public static List<Map<String, Object>> rows() {
        CrawlConfig config = CrawlConfig.load();
        Map<String, String> sources = CrawlConfig.describeSources();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(CrawlConfig.THREADS_KEY, config.threads(), sources));
        rows.add(row(CrawlConfig.REQUESTS_PER_SECOND_KEY, config.requestsPerSecond(), sources));
        rows.add(row(CrawlConfig.MAX_PAGES_KEY, config.maxPages(), sources));
        rows.add(row(CrawlConfig.MAX_RETRIES_KEY, config.maxRetries(), sources));
        rows.add(row(CrawlConfig.RETRY_BACKOFF_MILLIS_KEY, config.retryBackoffMillis(), sources));
        rows.add(row(CrawlConfig.CIRCUIT_BREAKER_THRESHOLD_KEY, config.circuitBreakerThreshold(), sources));
        return rows;
    }

    private static Map<String, Object> row(String key, Object value, Map<String, String> sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("value", value);
        row.put("source", sources.get(key));
        return row;
    }
}
