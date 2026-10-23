package com.github.magicalmuggle.web;

import com.github.magicalmuggle.CrawlConfig;
import com.github.magicalmuggle.CrawlMonitor;
import com.github.magicalmuggle.CrawlSession;

// 把「怎么跑一次抓取」和「怎么管理运行状态」解耦，测试里可以换成不联网的实现。
public interface CrawlLauncher {
    CrawlExecution start(CrawlConfig config, CrawlSession session, CrawlMonitor monitor) throws Exception;
}
