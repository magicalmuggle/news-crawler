package com.github.magicalmuggle.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 只读管理界面。JDK 自带的 HttpServer 足够用，不引入 Servlet 容器。
// 默认只监听回环地址，且没有任何认证：不要把它暴露到公网。
public final class AdminServer implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);
    private static final int WORKER_THREADS = 8;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ApiRoutes routes;
    private final StaticFiles staticFiles = new StaticFiles();
    private volatile boolean running;

    public AdminServer(AdminDao dao, LogTailer logs, RunManager runs, int port, String bindAddress)
            throws IOException {
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "admin-http");
            thread.setDaemon(true);
            return thread;
        });
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        this.routes = new ApiRoutes(dao, logs, runs, new HealthChecker(dao, logs), this::isRunning);
    }

    public void start() {
        server.start();
        running = true;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        server.stop(0);
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                routes.handle(exchange, method, path);
            } else {
                if (!"GET".equalsIgnoreCase(method)) {
                    throw new ApiException(405, "only GET is supported");
                }
                staticFiles.serve(exchange, path);
            }
        } catch (ApiException e) {
            HttpSupport.sendError(exchange, e.getStatus(), e.getMessage());
        } catch (IOException e) {
            // 客户端提前断开（SSE、关页面）属于正常情况
            LOG.debug("connection closed while handling {}", exchange.getRequestURI(), e);
        } catch (RuntimeException e) {
            LOG.error("unexpected failure while handling {}", exchange.getRequestURI(), e);
            HttpSupport.sendError(exchange, 500, "internal server error");
        } finally {
            exchange.close();
        }
    }
}
