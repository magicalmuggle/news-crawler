package com.github.magicalmuggle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerResilienceTest {

    @Test
    void keepsWorkingAfterOneLinkFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/news", exchange -> {
            byte[] body = "<html><body><article><h1>title</h1><p>content</p></article></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();
        try {
            int workingPort = server.getAddress().getPort();
            int closedPort = findClosedPort();
            String brokenLink = "http://127.0.0.1:" + closedPort + "/news";
            String goodLink = "http://127.0.0.1:" + workingPort + "/news";

            FakeCrawlerDao dao = new FakeCrawlerDao(Arrays.asList(brokenLink, goodLink));
            try (CrawlSession session = new CrawlSession(testConfig())) {
                Crawler crawler = new Crawler(dao, session);
                crawler.start();
                crawler.join(10_000L);

                assertFalse(crawler.isAlive(), "crawler thread should finish");
                assertFalse(dao.processedLinks.contains(brokenLink),
                        "a link that failed at the transport level must stay retryable");
                assertTrue(dao.processedLinks.contains(goodLink), "good link should be marked as processed");
                assertEquals(1, dao.news.size());
                assertEquals(goodLink, dao.news.get(0)[0]);
                assertEquals(1, session.droppedLinks());
            }
        } finally {
            server.stop(0);
        }
    }

    private static CrawlConfig testConfig() {
        return new CrawlConfig(1, 1000.0d, 10, 2, 1L, 10);
    }

    private static int findClosedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
