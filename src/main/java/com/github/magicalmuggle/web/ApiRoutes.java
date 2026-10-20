package com.github.magicalmuggle.web;

import com.github.magicalmuggle.News;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// 只读 API。这里不存在任何写操作的入口，是 P0 阶段最重要的约束。
public final class ApiRoutes {
    private static final int MAX_TEXT_PARAM_LENGTH = 100;

    private final AdminDao dao;
    private final LogTailer logs;
    private final HealthChecker health;
    private final BooleanSupplier running;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ApiRoutes(AdminDao dao, LogTailer logs, HealthChecker health, BooleanSupplier running) {
        this.dao = dao;
        this.logs = logs;
        this.health = health;
        this.running = running;
    }

    public void handle(HttpExchange exchange, String path) throws IOException {
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
            default:
                if (path.startsWith("/api/news/")) {
                    newsDetail(exchange, path);
                } else {
                    throw new ApiException(404, "unknown endpoint: " + path);
                }
        }
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
        String rawId = path.substring("/api/news/".length());
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "news id must be a number");
        }
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
}
