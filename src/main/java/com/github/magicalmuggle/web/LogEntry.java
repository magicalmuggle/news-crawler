package com.github.magicalmuggle.web;

// 一行日志的结构化形式。解析失败的行会并入上一条的 message，避免堆栈被拆散。
public final class LogEntry {
    private final String time;
    private final String level;
    private final String thread;
    private final String logger;
    private final String message;

    public LogEntry(String time, String level, String thread, String logger, String message) {
        this.time = time;
        this.level = level;
        this.thread = thread;
        this.logger = logger;
        this.message = message;
    }

    public String getTime() {
        return time;
    }

    public String getLevel() {
        return level;
    }

    public String getThread() {
        return thread;
    }

    public String getLogger() {
        return logger;
    }

    public String getMessage() {
        return message;
    }

    public LogEntry append(String extra) {
        return new LogEntry(time, level, thread, logger, message + "\n" + extra);
    }
}
