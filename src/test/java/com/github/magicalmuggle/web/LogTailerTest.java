package com.github.magicalmuggle.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailerTest {

    private LogTailer tailerWith(Path tempDir, String... lines) throws Exception {
        Path logFile = tempDir.resolve("crawler.log");
        Files.write(logFile, Arrays.asList(lines), StandardCharsets.UTF_8);
        return new LogTailer(logFile);
    }

    @Test
    void parsesStructuredLinesAndMergesContinuations(@TempDir Path tempDir) throws Exception {
        LogTailer tailer = tailerWith(tempDir,
                "2026-10-20 10:00:00.123 INFO  [crawler-0] c.g.m.Crawler - fetch https://sina.cn/ (attempt 1/4)",
                "2026-10-20 10:00:00.456 WARN  [crawler-0] c.g.m.Crawler - blocked with status 429",
                "java.net.SocketTimeoutException: Read timed out",
                "\tat org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator.connect",
                "2026-10-20 10:00:01.000 ERROR [main] c.g.m.Crawler - boom");

        List<LogEntry> entries = tailer.recent(10, null, null);

        assertEquals(3, entries.size());
        assertEquals("INFO", entries.get(0).getLevel());
        assertEquals("crawler-0", entries.get(0).getThread());
        assertEquals("c.g.m.Crawler", entries.get(0).getLogger());
        assertTrue(entries.get(0).getMessage().contains("fetch https://sina.cn/"));
        assertTrue(entries.get(1).getMessage().contains("SocketTimeoutException"));
        assertEquals("ERROR", entries.get(2).getLevel());
    }

    @Test
    void filtersByLevelAndKeyword(@TempDir Path tempDir) throws Exception {
        LogTailer tailer = tailerWith(tempDir,
                "2026-10-20 10:00:00.123 INFO  [crawler-0] c.g.m.Crawler - fetch https://sina.cn/",
                "2026-10-20 10:00:00.456 WARN  [crawler-1] c.g.m.Crawler - blocked with status 429",
                "2026-10-20 10:00:00.789 ERROR [crawler-1] c.g.m.Crawler - circuit breaker tripped");

        assertEquals(1, tailer.recent(10, "WARN", null).size());
        assertEquals(1, tailer.recent(10, null, "429").size());
        assertEquals(2, tailer.recent(10, null, "crawler-1").size());
        assertEquals(0, tailer.recent(10, "ERROR", "429").size());
    }

    @Test
    void returnsOnlyTheRequestedTail(@TempDir Path tempDir) throws Exception {
        LogTailer tailer = tailerWith(tempDir,
                "2026-10-20 10:00:00.000 INFO  [main] c.g.m.Crawler - one",
                "2026-10-20 10:00:01.000 INFO  [main] c.g.m.Crawler - two",
                "2026-10-20 10:00:02.000 INFO  [main] c.g.m.Crawler - three");

        List<LogEntry> entries = tailer.recent(2, null, null);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).getMessage().endsWith("two"));
        assertTrue(entries.get(1).getMessage().endsWith("three"));
    }

    @Test
    void toleratesMissingLogFile(@TempDir Path tempDir) {
        LogTailer tailer = new LogTailer(tempDir.resolve("missing.log"));

        assertEquals(0, tailer.recent(10, null, null).size());
        assertEquals(0L, tailer.size());
    }
}
