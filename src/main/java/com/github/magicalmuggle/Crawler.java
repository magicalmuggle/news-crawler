package com.github.magicalmuggle;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.ibatis.exceptions.PersistenceException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Crawler extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(Crawler.class);
    private static final int MAX_LOGGED_URL_LENGTH = 300;
    private static final long MAX_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private final CrawlerDao dao;
    private final CrawlSession session;
    private final CrawlMonitor monitor;

    public Crawler(CrawlerDao dao, CrawlSession session) {
        this(dao, session, null);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public Crawler(CrawlerDao dao, CrawlSession session, CrawlMonitor monitor) {
        this.dao = dao;
        this.session = session;
        this.monitor = monitor;
    }

    @Override
    public void run() {
        if (monitor != null) {
            monitor.register(getName());
        }
        try {
            while (session.shouldContinue()) {
                String link = dao.getNextLinkThenDelete();
                if (link == null) {
                    break;
                }
                if (dao.isLinkProcessed(link)) {
                    continue;
                }
                processLink(link);
                if (monitor != null) {
                    monitor.idle(getName());
                }
            }
        } catch (SQLException | PersistenceException e) {
            LOG.error("database failure while reading the link queue", e);
            throw new RuntimeException(e);
        } finally {
            if (monitor != null) {
                monitor.unregister(getName());
            }
        }
    }

    public void crawlTheIndexPage() {
        String indexLink = "https://sina.cn/";

        try {
            if (dao.isLinkProcessed(indexLink)) {
                return;
            }
            LOG.info("fetch index {}", indexLink);
            FetchResult result = fetchWithRetry(indexLink);
            if (result.getOutcome() == FetchResult.Outcome.OK) {
                parseUrlsFromPageAndStoreIntoDatabase(result.getDocument());
                dao.insertLinkAlreadyProcessed(indexLink, "OK");
            } else {
                LOG.warn("index page not fetched, status {}, the queue may stay empty", result.getStatusCode());
            }
        } catch (SQLException | PersistenceException e) {
            LOG.error("database failure while crawling the index page", e);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            LOG.warn("skip {} <- {}", indexLink, e.toString());
            LOG.debug("skip detail", e);
        }
    }

    private void processLink(String link) {
        if (!session.tryConsumePage()) {
            return;
        }
        try {
            FetchResult result = fetchWithRetry(link);
            switch (result.getOutcome()) {
                case OK:
                    parseUrlsFromPageAndStoreIntoDatabase(result.getDocument());
                    storeIntoDatabaseIfItIsNewsPage(result.getDocument(), link);
                    dao.insertLinkAlreadyProcessed(link, "OK");
                    break;
                case DEAD:
                    LOG.info("drop dead link {} ({})", abbreviate(link), result.getStatusCode());
                    dao.insertLinkAlreadyProcessed(link, "DEAD");
                    break;
                default:
                    // 被拦或网络错误：不标记为已处理，下轮从首页重新发现时还有机会重爬。
                    // 手动停止导致的提前退出不算失败，也不进入丢弃计数。
                    if (!session.isStopped()) {
                        recordFailure(link, result);
                        session.recordLinkDropped();
                    }
                    break;
            }
        } catch (SQLException | PersistenceException e) {
            LOG.error("database failure while processing {}", abbreviate(link), e);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            LOG.warn("skip {} <- {}", abbreviate(link), e.toString());
            LOG.debug("skip detail", e);
            markLinkAsProcessed(link);
        }
    }

    // 每次尝试前都过一遍全局限速；403/429/503 与传输错误按指数退避重试。
    // 返回最后一次的结果，交给调用方决定是否标记已处理。
    private FetchResult fetchWithRetry(String link) {
        FetchResult last = null;
        long waitMillis = 0L;
        int maxAttempts = session.maxRetries() + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                if (monitor != null) {
                    monitor.waiting(getName(), link);
                }
                sleepMillis(waitMillis);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            if (!session.shouldContinue()) {
                break;
            }
            session.acquireSlot();
            LOG.info("fetch {} (attempt {}/{})", abbreviate(link), attempt, maxAttempts);
            if (monitor != null) {
                monitor.fetching(getName(), link, attempt);
            }
            try {
                last = session.fetch(link);
            } catch (IOException e) {
                LOG.warn("transport error on {} <- {}", abbreviate(link), e.toString());
                LOG.debug("transport error detail", e);
                last = FetchResult.transientError(FetchResult.NO_STATUS);
            }

            switch (last.getOutcome()) {
                case OK:
                case DEAD:
                    session.recordSuccessfulResponse();
                    return last;
                case RATE_LIMITED:
                    session.recordBlockedResponse();
                    LOG.warn("blocked with status {} on {} (attempt {}/{})", last.getStatusCode(),
                            abbreviate(link), attempt, maxAttempts);
                    waitMillis = Math.max(backoffMillis(attempt), last.getRetryAfterMillis());
                    break;
                default:
                    waitMillis = backoffMillis(attempt);
                    break;
            }
        }

        if (last == null) {
            last = FetchResult.transientError(FetchResult.NO_STATUS);
        }
        return last;
    }

    private long backoffMillis(int attempt) {
        long base = Math.max(1L, session.retryBackoffMillis());
        if (base >= MAX_BACKOFF_MILLIS) {
            return MAX_BACKOFF_MILLIS;
        }
        long multiplier = 1L << Math.min(attempt - 1, 10);
        return Math.min(base * multiplier, MAX_BACKOFF_MILLIS);
    }

    private void sleepMillis(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markLinkAsProcessed(String link) {
        try {
            dao.insertLinkAlreadyProcessed(link, "SKIPPED");
        } catch (SQLException | PersistenceException e) {
            LOG.error("failed to mark {} as processed", abbreviate(link), e);
            throw new RuntimeException(e);
        }
    }

    // 失败链接只记录到 failed_links，绝不写 links_already_processed；
    // 记录失败本身出问题也不能让线程退出，否则一张表缺失就会拖垮整轮抓取。
    private void recordFailure(String link, FetchResult result) {
        try {
            dao.insertFailedLink(link, result.getStatusCode(), result.getOutcome().name());
        } catch (SQLException | PersistenceException e) {
            LOG.warn("failed to record the failure of {}: {}", abbreviate(link), e.toString());
        }
    }

    private String abbreviate(String link) {
        if (link.length() <= MAX_LOGGED_URL_LENGTH) {
            return link;
        }
        return link.substring(0, MAX_LOGGED_URL_LENGTH) + "...";
    }

    private void parseUrlsFromPageAndStoreIntoDatabase(Document doc) throws SQLException {
        Elements aTags = doc.select("a");
        for (Element aTag : aTags) {
            String link = aTag.attr("href");
            if (isRequiredLink(link)) {
                if (link.startsWith("//")) {
                    link = "https:" + link;
                }
                dao.insertLinkToBeProcessed(link.replaceAll("\\s", ""));
            }
        }
    }

    private void storeIntoDatabaseIfItIsNewsPage(Document doc, String link) throws SQLException {
        Elements articleTags = doc.select("article");
        if (!articleTags.isEmpty()) {
            Element articleTag = articleTags.get(0);
            String title = articleTag.child(0).text();
            String content = articleTag.select("p").stream().map(Element::text)
                    .collect(Collectors.joining("\n"));
            if (!title.isEmpty() && !content.isEmpty()) {
                dao.insertNewsIntoDatabase(link, title, content);
                session.recordNewsStored();
            }
        }
    }

    private boolean isRequiredLink(String link) {
        return LinkPolicy.check(link).isAccepted();
    }
}
