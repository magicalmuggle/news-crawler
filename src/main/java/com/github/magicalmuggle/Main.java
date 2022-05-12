package com.github.magicalmuggle;

public class Main {
    public static void main(String[] args) {
        int numOfThreads = 16;
        CrawlerDao dao = new MyBatisCrawlerDao();
        for (int i = 0; i < numOfThreads; i++) {
            new Crawler(dao).start();
        }
    }
}
