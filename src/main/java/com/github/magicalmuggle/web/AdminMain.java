package com.github.magicalmuggle.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

// 只读管理界面的入口。和 Main 完全独立：它不启动爬虫，也不写数据库。
public final class AdminMain {
    private static final Logger LOG = LoggerFactory.getLogger(AdminMain.class);
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_BIND = "127.0.0.1";

    private AdminMain() {
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String bind = DEFAULT_BIND;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = parsePort(args[++i]);
            } else if ("--bind".equals(args[i]) && i + 1 < args.length) {
                bind = args[++i];
            } else {
                usage("unknown argument: " + args[i]);
            }
        }

        AdminDao dao = new AdminDao();
        LogTailer logs = new LogTailer(LogTailer.defaultLogFile());
        try {
            AdminServer server = new AdminServer(dao, logs, port, bind);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                dao.close();
            }, "admin-shutdown"));
            server.start();
            LOG.info("admin ui listening on http://{}:{}/", bind, server.port());
            LOG.info("log file: {}", logs.logFile().toAbsolutePath());
            LOG.warn("read-only, no authentication: only bind to 127.0.0.1 or a trusted network");
            Thread.currentThread().join();
        } catch (IOException e) {
            LOG.error("failed to start the admin ui on {}:{}: {}", bind, port, e.getMessage());
            dao.close();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dao.close();
        }
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 0 || port > 65535) {
                usage("port must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            usage("port must be a number: " + raw);
            return DEFAULT_PORT;
        }
    }

    private static void usage(String message) {
        throw new IllegalArgumentException(message
                + " (usage: AdminMain [--port 8080] [--bind 127.0.0.1])");
    }
}
