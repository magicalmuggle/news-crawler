package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerResilienceTest {

    @Test
    void keepsWorkingAfterOneLinkFails() throws Exception {
        StubHttpServer server = new StubHttpServer(200,
                "<html><body><article><h1>title</h1><p>content</p></article></body></html>");
        try {
            int workingPort = server.port();
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
            server.close();
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
