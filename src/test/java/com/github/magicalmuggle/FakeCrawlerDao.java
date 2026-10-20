package com.github.magicalmuggle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 不连数据库的测试替身：队列是内存里的 list，写操作只记录。
final class FakeCrawlerDao implements CrawlerDao {
    final List<String> processedLinks = Collections.synchronizedList(new ArrayList<String>());
    final List<String[]> news = Collections.synchronizedList(new ArrayList<String[]>());

    private final List<String> queue;

    FakeCrawlerDao(List<String> queue) {
        this.queue = new ArrayList<>(queue);
    }

    @Override
    public synchronized String getNextLinkThenDelete() {
        if (queue.isEmpty()) {
            return null;
        }
        return queue.remove(0);
    }

    @Override
    public void insertNewsIntoDatabase(String url, String title, String content) {
        news.add(new String[]{url, title, content});
    }

    @Override
    public boolean isLinkProcessed(String link) {
        return processedLinks.contains(link);
    }

    @Override
    public void insertLinkToBeProcessed(String link) {
    }

    @Override
    public void insertLinkAlreadyProcessed(String link) {
        processedLinks.add(link);
    }
}
