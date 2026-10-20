package com.github.magicalmuggle.web;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import com.github.magicalmuggle.ElasticsearchUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 环境自检：数据库、Elasticsearch、证书、日志文件、配置来源。
// 结果缓存几秒，避免每次刷新页面都去 ping ES。
public final class HealthChecker {
    private static final long CACHE_MILLIS = 5000L;
    private static final long ES_TIMEOUT_SECONDS = 4L;
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final AdminDao dao;
    private final LogTailer logs;
    private final Path certificatePath = Paths.get("./http_ca.crt");

    private Map<String, Object> cached;
    private long cachedAt;

    public HealthChecker(AdminDao dao, LogTailer logs) {
        this.dao = dao;
        this.logs = logs;
    }

    public synchronized Map<String, Object> check() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MILLIS) {
            // 返回浅拷贝：调用方只会序列化，但不要让它拿到缓存字段本身
            return new LinkedHashMap<>(cached);
        }
        Map<String, Object> result = build();
        cached = result;
        cachedAt = now;
        return new LinkedHashMap<>(result);
    }

    private Map<String, Object> build() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkedAt", Instant.now());
        body.put("database", database());
        body.put("elasticsearch", elasticsearch());
        body.put("certificate", certificate());
        body.put("logs", logFile());
        body.put("config", ConfigReport.rows());
        return body;
    }

    private Map<String, Object> database() {
        Map<String, Object> database = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            dao.ping();
        } catch (RuntimeException e) {
            database.put("status", "DOWN");
            database.put("message", describe(e));
            return database;
        }
        database.put("status", "UP");
        database.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        putQuietly(database, "name", this::databaseName);
        putQuietly(database, "version", this::databaseVersion);
        putQuietly(database, "flywayVersion", this::flywayVersion);
        return database;
    }

    private String databaseName() {
        return dao.databaseName();
    }

    private String databaseVersion() {
        return dao.databaseVersion();
    }

    private String flywayVersion() {
        return dao.flywayVersion();
    }

    private void putQuietly(Map<String, Object> target, String key, ValueSupplier supplier) {
        try {
            String value = supplier.get();
            if (value != null) {
                target.put(key, value);
            }
        } catch (RuntimeException ignored) {
            // 老库可能没有 flyway_schema_history，缺这一项不影响连通性结论
        }
    }

    private Map<String, Object> elasticsearch() {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "es-health-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Map<String, Object>> future = executor.submit(this::probeElasticsearch);
            return future.get(ES_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return down("timed out after " + ES_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down("interrupted");
        } catch (ExecutionException e) {
            return down(describe(e.getCause()));
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> probeElasticsearch() throws IOException {
        Map<String, Object> elasticsearch = new LinkedHashMap<>();
        ElasticsearchClient client = ElasticsearchUtil.getElasticsearchClient();
        try {
            InfoResponse info = client.info();
            elasticsearch.put("status", "UP");
            elasticsearch.put("clusterName", info.clusterName());
            elasticsearch.put("version", info.version().number());
            elasticsearch.put("newsDocuments", client.count(count -> count.index("news")).count());
        } finally {
            client._transport().close();
        }
        return elasticsearch;
    }

    private Map<String, Object> certificate() {
        Map<String, Object> certificate = new LinkedHashMap<>();
        certificate.put("path", certificatePath.toString());
        boolean readable = Files.isReadable(certificatePath);
        certificate.put("status", readable ? "UP" : "DOWN");
        if (!readable) {
            certificate.put("message", "missing, run docker cp to copy it from the Elasticsearch container");
            return certificate;
        }
        try {
            certificate.put("sizeBytes", Files.size(certificatePath));
        } catch (IOException ignored) {
            // 读不到大小不影响「证书存在」这个结论
        }
        return certificate;
    }

    private Map<String, Object> logFile() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("file", logs.logFile().toString());
        log.put("sizeBytes", logs.size());
        try {
            if (Files.isRegularFile(logs.logFile())) {
                log.put("lastModified", Files.getLastModifiedTime(logs.logFile()).toInstant());
            }
        } catch (IOException ignored) {
            // 日志文件刚被轮转时可能消失
        }
        return log;
    }

    private Map<String, Object> down(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "DOWN");
        body.put("message", message);
        return body;
    }

    private String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        return message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) + "..."
                : message;
    }

    private interface ValueSupplier {
        String get();
    }
}
