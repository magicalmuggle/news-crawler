package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerBackoffTest {

    @Test
    void rateLimitedResponseIsNotMarkedAsProcessed() throws Exception {
        StubHttpServer server = startServer(429, "rate limited");
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
                assertEquals(1, dao.failedLinks.size(), "the dropped link should be recorded in failed_links");
                assertEquals("RATE_LIMITED", dao.failedLinks.get(0)[2]);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void notFoundLinkIsMarkedAsProcessed() throws Exception {
        StubHttpServer server = startServer(404, "not found");
        try {
            String link = linkFor(server);
            FakeCrawlerDao dao = new FakeCrawlerDao(Collections.singletonList(link));
            try (CrawlSession session = new CrawlSession(blockedFriendlyConfig())) {
                Crawler crawler = new Crawler(dao, session);
                crawler.start();
                crawler.join(10_000L);

                assertFalse(crawler.isAlive(), "crawler thread should finish");
                assertTrue(dao.processedLinks.contains(link), "404 should be recorded as processed");
                assertEquals("DEAD", dao.processedWithReason.get(0)[1]);
                assertTrue(dao.failedLinks.isEmpty(), "a 404 is not a failure");
                assertTrue(dao.news.isEmpty());
                assertFalse(session.isTripped());
                assertEquals(0, session.droppedLinks());
            }
        } finally {
            server.close();
        }
    }

    // 两个 429 就会熔断，退避 1ms，保证测试不真的等几秒。
    private static CrawlConfig blockedFriendlyConfig() {
        return new CrawlConfig(1, 1000.0d, 10, 1, 1L, 2);
    }

    private static StubHttpServer startServer(int status, String bodyText) throws Exception {
        return new StubHttpServer(status, bodyText);
    }

    private static String linkFor(StubHttpServer server) {
        return "http://127.0.0.1:" + server.port() + "/news";
    }
}
