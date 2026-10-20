package com.github.magicalmuggle.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdminDao dao;
    private AdminServer server;
    private Path logFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        H2TestSupport.resetSchema();
        H2TestSupport.insertNews("https://sina.cn/1", "第一条", "正文一", Instant.parse("2026-10-20T02:00:00Z"));
        H2TestSupport.insertNews("https://sina.cn/2", "第二条", "正文二", Instant.parse("2026-10-20T03:00:00Z"));
        H2TestSupport.insertLink("links_to_be_processed", "https://sina.cn/pending");

        logFile = tempDir.resolve("crawler.log");
        Files.write(logFile, Arrays.asList(
                "2026-10-20 10:00:00.123 INFO  [crawler-0] c.g.m.Crawler - hello",
                "2026-10-20 10:00:00.456 WARN  [crawler-1] c.g.m.Crawler - blocked with status 429"),
                StandardCharsets.UTF_8);

        dao = new AdminDao(H2TestSupport.buildFactory());
        server = new AdminServer(dao, new LogTailer(logFile), 0, "127.0.0.1");
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (dao != null) {
            dao.close();
        }
    }

    @Test
    void servesOverview() throws Exception {
        Response response = request("/api/overview", "GET");

        assertEquals(200, response.status);
        JsonNode body = MAPPER.readTree(response.body);
        assertEquals(2, body.path("news").path("total").asInt());
        assertEquals(1, body.path("links").path("pending").asInt());
        assertEquals(0, body.path("links").path("processed").asInt());
    }

    @Test
    void servesNewsListAndDetail() throws Exception {
        Response list = request("/api/news?limit=10", "GET");
        assertEquals(200, list.status);
        JsonNode items = MAPPER.readTree(list.body).path("items");
        assertEquals(2, items.size());
        long id = items.get(0).path("id").asLong();

        Response detail = request("/api/news/" + id, "GET");
        assertEquals(200, detail.status);
        assertEquals("第二条", MAPPER.readTree(detail.body).path("title").asText());

        assertEquals(404, request("/api/news/999999", "GET").status);
        assertEquals(400, request("/api/news/abc", "GET").status);
    }

    @Test
    void servesLinksAndLogs() throws Exception {
        JsonNode links = MAPPER.readTree(request("/api/links/pending?limit=10", "GET").body);
        assertEquals(1, links.path("total").asInt());
        assertEquals(1, links.path("items").size());

        Response logs = request("/api/logs?limit=10", "GET");
        assertEquals(200, logs.status);
        JsonNode entries = MAPPER.readTree(logs.body).path("items");
        assertEquals(2, entries.size());
        assertTrue(entries.get(1).path("message").asText().contains("429"));
    }

    @Test
    void servesStaticFiles() throws Exception {
        Response index = request("/", "GET");
        assertEquals(200, index.status);
        assertTrue(index.body.contains("news-crawler"));

        Response script = request("/app.js", "GET");
        assertEquals(200, script.status);
        assertTrue(script.body.contains("renderOverview"));
    }

    @Test
    void rejectsUnknownEndpointsAndMethods() throws Exception {
        assertEquals(404, request("/api/nope", "GET").status);
        assertEquals(404, request("/secret.txt", "GET").status);
        assertEquals(405, request("/api/overview", "POST").status);
    }

    @Test
    void reportsHealthWithoutLeakingSecrets() throws Exception {
        Response response = request("/api/health", "GET");

        assertEquals(200, response.status);
        JsonNode body = MAPPER.readTree(response.body);
        assertEquals("UP", body.path("database").path("status").asText());
        assertEquals(6, body.path("config").size());
        assertFalse(response.body.contains("password"));
    }

    @Test
    void streamsNewLogLinesOverSse() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://127.0.0.1:" + server.port() + "/api/logs/stream").toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "text/event-stream");

        StringBuilder received = new StringBuilder();
        try (InputStream stream = connection.getInputStream()) {
            Files.write(logFile, Arrays.asList(
                    "2026-10-20 10:00:02.000 INFO  [sse] c.g.m.Crawler - streamed line"),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            byte[] buffer = new byte[512];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !received.toString().contains("streamed line")) {
                int read = stream.read(buffer);
                if (read < 0) {
                    break;
                }
                received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }

        assertTrue(received.toString().contains("event: log"), received.toString());
        assertTrue(received.toString().contains("streamed line"), received.toString());
    }

    private Response request(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://127.0.0.1:" + server.port() + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : readAll(stream);
        connection.disconnect();
        return new Response(status, body);
    }

    private String readAll(InputStream stream) throws IOException {
        try (InputStream inputStream = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = inputStream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
