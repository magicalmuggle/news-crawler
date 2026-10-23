package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.News;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// 管理界面的 API。只读查询走 AdminDao，运行控制走 RunManager，
// 写路径仅限「crawl_runs 运行历史」这一张表。
public final class ApiRoutes {
    private static final int MAX_TEXT_PARAM_LENGTH = 100;
    private static final int MAX_UI_THREADS = 8;
    private static final double MAX_UI_RATE = 5.0d;

    private final AdminDao dao;
    private final LogTailer logs;
    private final RunManager runs;
    private final HealthChecker health;
    private final BooleanSupplier running;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ApiRoutes(AdminDao dao, LogTailer logs, RunManager runs, HealthChecker health,
            BooleanSupplier running) {
        this.dao = dao;
        this.logs = logs;
        this.runs = runs;
        this.health = health;
        this.running = running;
    }

    public void handle(HttpExchange exchange, String method, String path) throws IOException {
        if ("/api/runs".equals(path)) {
            handleRunsCollection(exchange, method);
            return;
        }
        if ("/api/runs/current".equals(path)) {
            requireGet(method);
            HttpSupport.sendJson(exchange, 200, runs.snapshot());
            return;
        }
        if ("/api/runs/stream".equals(path)) {
            requireGet(method);
            streamRuns(exchange);
            return;
        }
        if (path.startsWith("/api/runs/")) {
            handleRunAction(exchange, method, path);
            return;
        }

        requireGet(method);
        Map<String, String> params = HttpSupport.queryParams(exchange);
        switch (path) {
            case "/api/overview":
                overview(exchange);
                break;
            case "/api/news":
                news(exchange, params);
                break;
            case "/api/links/pending":
                pendingLinks(exchange, params);
                break;
            case "/api/links/processed":
                processedLinks(exchange, params);
                break;
            case "/api/logs":
                logEntries(exchange, params);
                break;
            case "/api/logs/stream":
                logs.stream(exchange, running);
                break;
            case "/api/health":
                HttpSupport.sendJson(exchange, 200, health.check());
                break;
            case "/api/config/effective":
                configEffective(exchange);
                break;
            default:
                if (path.startsWith("/api/news/")) {
                    newsDetail(exchange, path);
                } else {
                    throw new ApiException(404, "unknown endpoint: " + path);
                }
        }
    }

    private void handleRunsCollection(HttpExchange exchange, String method) throws IOException {
        if ("POST".equalsIgnoreCase(method)) {
            startRun(exchange);
            return;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            throw new ApiException(405, "method not allowed");
        }
        int limit = HttpSupport.limitParam(HttpSupport.queryParams(exchange));
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("available", true);
            body.put("items", runs.history(limit));
        } catch (RuntimeException e) {
            body.put("available", false);
            body.put("items", Collections.emptyList());
            body.put("message", "crawl_runs 表不可用，请先执行 mvn flyway:migrate");
        }
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void handleRunAction(HttpExchange exchange, String method, String path) throws IOException {
        String rest = path.substring("/api/runs/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            requireGet(method);
            long id = parseId(rest);
            RunRecord record;
            try {
                record = runs.historyEntry(id);
            } catch (RuntimeException e) {
                throw new ApiException(503, "crawl_runs 表不可用，请先执行 mvn flyway:migrate");
            }
            if (record == null) {
                throw new ApiException(404, "run not found");
            }
            HttpSupport.sendJson(exchange, 200, record);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            throw new ApiException(405, "method not allowed");
        }
        if (!"stop".equals(rest.substring(slash + 1))) {
            throw new ApiException(404, "unknown endpoint: " + path);
        }
        stopRun(exchange, parseId(rest.substring(0, slash)));
    }

    private void startRun(HttpExchange exchange) throws IOException {
        StartRunRequest request;
        try {
            request = Json.read(HttpSupport.readBody(exchange), StartRunRequest.class);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "请求体不是合法的 JSON");
        }
        if (request == null) {
            request = new StartRunRequest();
        }
        CrawlConfig defaults = CrawlConfig.load();
        CrawlConfig config;
        try {
            config = new CrawlConfig(
                    orDefault(request.threads, defaults.threads()),
                    orDefault(request.requestsPerSecond, defaults.requestsPerSecond()),
                    orDefault(request.maxPages, defaults.maxPages()),
                    orDefault(request.maxRetries, defaults.maxRetries()),
                    orDefault(request.retryBackoffMillis, defaults.retryBackoffMillis()),
                    orDefault(request.circuitBreakerThreshold, defaults.circuitBreakerThreshold()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (request.threads != null && request.threads > MAX_UI_THREADS) {
            throw new ApiException(400, "threads 最多 " + MAX_UI_THREADS + " 个");
        }
        if (request.requestsPerSecond != null && request.requestsPerSecond > MAX_UI_RATE) {
            throw new ApiException(400, "ratePerSecond 最多 " + MAX_UI_RATE + "，别把界面当成提速开关");
        }
        try {
            runs.start(config);
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
        HttpSupport.sendJson(exchange, 202, runs.snapshot());
    }

    private void stopRun(HttpExchange exchange, long id) throws IOException {
        RunSnapshot current = runs.snapshot();
        if (!current.getState().isActive()) {
            throw new ApiException(409, "当前没有运行中的任务");
        }
        if (current.getRunId() != null && current.getRunId() != id) {
            throw new ApiException(409, "run " + id + " 不是当前运行");
        }
        try {
            runs.stop();
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
        HttpSupport.sendJson(exchange, 200, runs.snapshot());
    }

    private void streamRuns(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        OutputStream outputStream = exchange.getResponseBody();
        outputStream.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        try {
            while (running.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                String payload = new String(Json.toBytes(runs.snapshot()), StandardCharsets.UTF_8);
                outputStream.write(("event: tick\ndata: " + payload + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                Thread.sleep(1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                outputStream.close();
            } catch (IOException ignored) {
                // 客户端已断开
            }
        }
    }

    private void configEffective(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", ConfigReport.rows());
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void overview(HttpExchange exchange) throws IOException {
        Map<String, Object> news = new LinkedHashMap<>();
        news.put("total", query(dao::countNews));
        news.put("today", query(() -> dao.countNewsSince(startOfToday())));
        news.put("lastCreatedAt", query(dao::latestNewsCreatedAt));

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("pending", query(dao::countLinksPending));
        links.put("processed", query(dao::countLinksProcessed));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("news", news);
        body.put("links", links);
        body.put("run", runs.snapshot());
        body.put("logFile", logs.logFile().toString());
        body.put("generatedAt", Instant.now());
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void news(HttpExchange exchange, Map<String, String> params) throws IOException {
        int limit = HttpSupport.limitParam(params);
        Long beforeId = HttpSupport.longParam(params, "beforeId");
        String title = likePattern(truncate(HttpSupport.stringParam(params, "title")));

        List<NewsSummary> items = query(() -> dao.pageNews(beforeId, title, limit));
        Long nextBeforeId = items.isEmpty() ? null : items.get(items.size() - 1).getId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextBeforeId", nextBeforeId);
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void newsDetail(HttpExchange exchange, String path) throws IOException {
        long id = parseId(path.substring("/api/news/".length()));
        News news = query(() -> dao.findNews(id));
        if (news == null) {
            throw new ApiException(404, "news not found");
        }
        HttpSupport.sendJson(exchange, 200, news);
    }

    private void pendingLinks(HttpExchange exchange, Map<String, String> params) throws IOException {
        int limit = HttpSupport.limitParam(params);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", query(dao::countLinksPending));
        body.put("items", query(() -> dao.pendingLinks(limit)));
        body.put("duplicates", query(() -> dao.duplicatePendingLinks(20)));
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void processedLinks(HttpExchange exchange, Map<String, String> params) throws IOException {
        int limit = HttpSupport.limitParam(params);
        String keyword = likePattern(truncate(HttpSupport.stringParam(params, "q")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", query(dao::countLinksProcessed));
        body.put("items", query(() -> dao.processedLinks(keyword, limit)));
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void logEntries(HttpExchange exchange, Map<String, String> params) throws IOException {
        int limit = HttpSupport.limitParam(params);
        String level = HttpSupport.stringParam(params, "level");
        String query = HttpSupport.stringParam(params, "q");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", logs.recent(limit, level, query));
        body.put("file", logs.logFile().toString());
        body.put("sizeBytes", logs.size());
        HttpSupport.sendJson(exchange, 200, body);
    }

    private void requireGet(String method) {
        if (!"GET".equalsIgnoreCase(method)) {
            throw new ApiException(405, "only GET is supported");
        }
    }

    private long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "id must be a number");
        }
    }

    private Instant startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_PARAM_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_PARAM_LENGTH);
    }

    // MySQL 的 like 默认用反斜杠转义，用户输入的 % 和 _ 不应该被当成通配符
    private String likePattern(String value) {
        if (value == null) {
            return null;
        }
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private <T> T query(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw new ApiException(503, "database unavailable: " + rootMessage(e));
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

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    // 只用于接收入参，字段名和前端表单一一对应
    private static final class StartRunRequest {
        public Integer threads;
        public Double requestsPerSecond;
        public Integer maxPages;
        public Integer maxRetries;
        public Long retryBackoffMillis;
        public Integer circuitBreakerThreshold;
    }
}
