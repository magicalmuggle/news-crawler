package com.github.magicalmuggle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        CrawlConfig config = CrawlConfig.load();
        LOG.info("crawl config: {}", config);

        try (CrawlSession session = new CrawlSession(config)) {
            CrawlerDao dao = new MyBatisCrawlerDao();

            /*
            如果没有爬取过首页，那就在多线程运行之前爬取一次。
            防止出现链接池中的链接数太少，导致线程未获取到链接直接结束运行。
             */
            new Crawler(dao, session).crawlTheIndexPage();

            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < config.threads(); i++) {
                Crawler crawler = new Crawler(dao, session);
                crawler.setName("crawler-" + i);
                crawler.setUncaughtExceptionHandler((thread, throwable) ->
                        LOG.error("crawler thread {} stopped with an error", thread.getName(), throwable));
                workers.add(crawler);
                crawler.start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
            logSummary(session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("interrupted, stopping the crawler");
        } catch (IOException e) {
            LOG.error("failed to close the HTTP client", e);
        }
    }

    private static void logSummary(CrawlSession session) {
        LOG.info("crawl finished: attemptedPages={}, newsStored={}, droppedLinks={}, remainingPages={}, "
                        + "consecutiveBlocks={}",
                session.attemptedPages(), session.storedNews(), session.droppedLinks(),
                session.remainingPages(), session.consecutiveBlocks());
        if (session.isTripped()) {
            LOG.warn("circuit breaker tripped: the site is probably rate limiting this client. "
                    + "Lower crawler.ratePerSecond and try again later.");
        }
    }
}
