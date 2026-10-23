package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.CrawlMonitor;
import com.github.magicalmuggle.CrawlSession;
import com.github.magicalmuggle.Crawler;
import com.github.magicalmuggle.CrawlerDao;
import com.github.magicalmuggle.MyBatisCrawlerDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

// 真正的抓取启动器：和 Main 的流程一致（先爬首页，再起 worker，最后 join）。
// 与管理界面同进程，这是「取链接不重复」所依赖的 synchronized 生效的前提。
public final class DefaultCrawlLauncher implements CrawlLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultCrawlLauncher.class);

    // DAO 只建一次：每次运行都 new 一个会把连接池也重建一遍
    private final CrawlerDao dao = new MyBatisCrawlerDao();

    @Override
    public CrawlExecution start(CrawlConfig config, CrawlSession session, CrawlMonitor monitor) {
        // 首页这一趟跑在运行线程上，不注册到 monitor，否则会留下一个永远 idle 的条目
        new Crawler(dao, session).crawlTheIndexPage();

        List<Crawler> workers = new ArrayList<>();
        for (int i = 0; i < config.threads(); i++) {
            Crawler crawler = new Crawler(dao, session, monitor);
            crawler.setName("crawler-" + i);
            crawler.setUncaughtExceptionHandler((thread, throwable) ->
                    LOG.error("crawler thread {} stopped with an error", thread.getName(), throwable));
            workers.add(crawler);
            crawler.start();
        }

        return new WorkerExecution(workers);
    }

    private static final class WorkerExecution implements CrawlExecution {
        private final List<Crawler> workers;

        private WorkerExecution(List<Crawler> workers) {
            this.workers = new ArrayList<>(workers);
        }

        @Override
        public void awaitCompletion() throws InterruptedException {
            for (Crawler worker : workers) {
                worker.join();
            }
        }

        @Override
        public void requestStop() {
            for (Crawler worker : workers) {
                worker.interrupt();
            }
        }
    }
}
