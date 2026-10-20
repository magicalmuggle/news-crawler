package com.github.magicalmuggle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerBackoffTest {

    @Test
    void rateLimitedResponseIsNotMarkedAsProcessed() throws Exception {
        HttpServer server = startServer(429, "rate limited");
        try {
            String link = linkFor(server);
            FakeCrawlerDao dao = new FakeCrawlerDao(Collections.singletonList(link));
            try (CrawlSession session = new CrawlSession(blockedFriendlyConfig())) {
                Crawler crawler = new Crawler(dao, session);
                crawler.start();
                crawler.join(10_000L);

                assertFalse(crawler.isAlive(), "crawler thread should finish");
                assertFalse(dao.processedLinks.contains(link), "429 must not mark the link as processed");
                assertTrue(dao.news.isEmpty(), "a blocked response must not produce news");
                assertTrue(session.isTripped(), "two blocked responses should trip the circuit breaker");
                assertEquals(1, session.droppedLinks());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notFoundLinkIsMarkedAsProcessed() throws Exception {
        HttpServer server = startServer(404, "not found");
        try {
            String link = linkFor(server);
            FakeCrawlerDao dao = new FakeCrawlerDao(Collections.singletonList(link));
            try (CrawlSession session = new CrawlSession(blockedFriendlyConfig())) {
                Crawler crawler = new Crawler(dao, session);
                crawler.start();
                crawler.join(10_000L);

                assertFalse(crawler.isAlive(), "crawler thread should finish");
                assertTrue(dao.processedLinks.contains(link), "404 should be recorded as processed");
                assertTrue(dao.news.isEmpty());
                assertFalse(session.isTripped());
                assertEquals(0, session.droppedLinks());
            }
        } finally {
            server.stop(0);
        }
    }

    // 两个 429 就会熔断，退避 1ms，保证测试不真的等几秒。
    private static CrawlConfig blockedFriendlyConfig() {
        return new CrawlConfig(1, 1000.0d, 10, 1, 1L, 2);
    }

    private static HttpServer startServer(int status, String bodyText) throws Exception {
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/news", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String linkFor(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/news";
    }
}
