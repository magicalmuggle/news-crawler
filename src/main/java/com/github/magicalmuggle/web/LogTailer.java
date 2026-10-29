package com.github.magicalmuggle.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 读取 Logback 写出的 crawler.log：历史查询按尾部截断，实时查询按文件增长量增量读取。
// 只读文件，不会和 Logback 争抢写入。
public final class LogTailer {
    private static final int MAX_TAIL_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LINES_PER_TICK = 100;
    private static final long POLL_MILLIS = 1000L;
    private static final int HEARTBEAT_TICKS = 15;
    private static final int MAX_CLIENT_LINE_BYTES = 8 * 1024;

    // 与 logback.xml 的 PATTERN 对应：时间 级别 [线程] logger - 消息
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+)\\s+\\[([^\\]]*)]\\s+(\\S+)\\s+-\\s(.*)$");

    private final Path logFile;

    public LogTailer(Path logFile) {
        this.logFile = logFile;
    }

    public static Path defaultLogFile() {
        String logDir = System.getProperty("LOG_DIR");
        if (logDir == null || logDir.trim().isEmpty()) {
            logDir = System.getenv("LOG_DIR");
        }
        if (logDir == null || logDir.trim().isEmpty()) {
            logDir = "logs";
        }
        return Paths.get(logDir.trim(), "crawler.log");
    }

    public Path logFile() {
        return logFile;
    }

    public long size() {
        try {
            return Files.isRegularFile(logFile) ? Files.size(logFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    public List<LogEntry> recent(int limit, String level, String query) {
        List<LogEntry> matched = new ArrayList<>();
        for (LogEntry entry : parse(readTail())) {
            if (matches(entry, level, query)) {
                matched.add(entry);
            }
        }
        int from = Math.max(0, matched.size() - limit);
        return new ArrayList<>(matched.subList(from, matched.size()));
    }

    // SSE：先把当前文件当作基线，之后只推送新增内容。客户端断开时抛出 IOException，由调用方忽略。
    public void stream(HttpSupport http, BooleanSupplier running) throws IOException {
        OutputStream outputStream = http.startEventStream();
        StringBuilder carry = new StringBuilder();
        long position = size();
        int idleTicks = 0;
        try {
            while (running.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                long length = size();
                if (length < position) {
                    // 文件被轮转/截断，从新文件开头重新读
                    position = 0L;
                    carry.setLength(0);
                }
                boolean wrote = false;
                if (length > position) {
                    byte[] chunk = readRange(position, length);
                    position = length;
                    carry.append(new String(chunk, StandardCharsets.UTF_8));
                    wrote = flushCompleteLines(outputStream, carry);
                }
                if (wrote) {
                    idleTicks = 0;
                } else {
                    idleTicks++;
                    if (idleTicks % HEARTBEAT_TICKS == 0) {
                        outputStream.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                // 客户端已断开
            }
        }
    }

    private boolean flushCompleteLines(OutputStream outputStream, StringBuilder carry) throws IOException {
        boolean wrote = false;
        int count = 0;
        while (count < MAX_LINES_PER_TICK) {
            int newline = carry.indexOf("\n");
            if (newline < 0) {
                if (carry.length() > MAX_CLIENT_LINE_BYTES) {
                    carry.setLength(0);
                }
                break;
            }
            String line = stripCarriageReturn(carry.substring(0, newline));
            carry.delete(0, newline + 1);
            if (line.isEmpty()) {
                continue;
            }
            List<LogEntry> entries = parse(Collections.singletonList(line));
            for (LogEntry entry : entries) {
                outputStream.write(("event: log\ndata: "
                        + new String(Json.toBytes(entry), StandardCharsets.UTF_8) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            wrote = true;
            count++;
        }
        if (wrote) {
            outputStream.flush();
        }
        return wrote;
    }

    private byte[] readRange(long from, long to) throws IOException {
        long available = to - from;
        int length = (int) Math.min(available, MAX_TAIL_BYTES);
        byte[] bytes = new byte[length];
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            file.seek(to - length);
            file.readFully(bytes);
        }
        return bytes;
    }

    private List<String> readTail() {
        if (!Files.isRegularFile(logFile)) {
            return Collections.emptyList();
        }
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            int readLength = (int) Math.min(length, MAX_TAIL_BYTES);
            file.seek(length - readLength);
            byte[] bytes = new byte[readLength];
            file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.add(stripCarriageReturn(line));
            }
            if (readLength < length && !lines.isEmpty()) {
                // 第一行是从文件中间截断的，丢掉
                lines.remove(0);
            }
            return lines;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private List<LogEntry> parse(List<String> lines) {
        List<LogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = LINE_PATTERN.matcher(line);
            if (matcher.matches()) {
                entries.add(new LogEntry(matcher.group(1), matcher.group(2), matcher.group(3),
                        matcher.group(4), matcher.group(5)));
            } else if (!entries.isEmpty() && !line.isEmpty()) {
                // 堆栈等续行并入上一条日志
                int last = entries.size() - 1;
                entries.set(last, entries.get(last).append(line));
            }
        }
        return entries;
    }

    private boolean matches(LogEntry entry, String level, String query) {
        if (level != null && !level.equalsIgnoreCase(entry.getLevel())) {
            return false;
        }
        if (query == null) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return entry.getMessage().toLowerCase(Locale.ROOT).contains(needle)
                || entry.getLogger().toLowerCase(Locale.ROOT).contains(needle)
                || entry.getThread().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
