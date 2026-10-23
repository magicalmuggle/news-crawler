package com.github.magicalmuggle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 记录每个抓取线程此刻在做什么，供 Web 界面展示。
// 只是旁路观测：不参与限速、不参与调度，读不到也不影响抓取。
public final class CrawlMonitor {
    private final Map<String, ThreadStatus> threads = new ConcurrentHashMap<>();

    public void register(String threadName) {
        threads.put(threadName, new ThreadStatus(threadName, null, 0, "idle"));
    }

    public void unregister(String threadName) {
        threads.remove(threadName);
    }

    public void fetching(String threadName, String link, int attempt) {
        threads.put(threadName, new ThreadStatus(threadName, link, attempt, "fetching"));
    }

    public void waiting(String threadName, String link) {
        threads.put(threadName, new ThreadStatus(threadName, link, 0, "waiting"));
    }

    public void idle(String threadName) {
        threads.put(threadName, new ThreadStatus(threadName, null, 0, "idle"));
    }

    public List<ThreadStatus> snapshot() {
        List<ThreadStatus> snapshot = new ArrayList<>(threads.values());
        snapshot.sort(Comparator.comparing(ThreadStatus::getName));
        return snapshot;
    }

    public static final class ThreadStatus {
        private final String name;
        private final String link;
        private final int attempt;
        private final String phase;

        ThreadStatus(String name, String link, int attempt, String phase) {
            this.name = name;
            this.link = link;
            this.attempt = attempt;
            this.phase = phase;
        }

        public String getName() {
            return name;
        }

        public String getLink() {
            return link;
        }

        public int getAttempt() {
            return attempt;
        }

        public String getPhase() {
            return phase;
        }
    }
}
