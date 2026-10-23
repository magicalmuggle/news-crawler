package com.github.magicalmuggle.web;

// 一次抓取的执行句柄：等待结束、请求停止。
public interface CrawlExecution {
    void awaitCompletion() throws InterruptedException;

    void requestStop();
}
