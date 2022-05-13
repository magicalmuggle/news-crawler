package com.github.magicalmuggle;

public class Main {
    public static void main(String[] args) {
        CrawlerDao dao = new MyBatisCrawlerDao();
        int numOfThreads = 16;

        /*
        如果没有爬取过首页，那就在多线程运行之前爬取一次。
        防止出现链接池中的链接数太少，导致线程未获取到链接直接结束运行。
         */
        new Crawler(dao).crawlTheIndexPage();

        for (int i = 0; i < numOfThreads; i++) {
            new Crawler(dao).start();
        }
    }
}
