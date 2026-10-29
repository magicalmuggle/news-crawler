package com.github.magicalmuggle.web;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;

// 管理界面跑在一个内嵌的 Jetty 上。以前用的是 JDK 自带的 com.sun.net.httpserver：
// 它位于 com.sun.* 命名空间，Eclipse 编译器（VSCode 的 Java 扩展）会把它当成受限 API 报错。
// 默认只监听回环地址，且没有任何认证：不要把它暴露到公网。
public final class AdminServer implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);
    private static final int WORKER_THREADS = 8;
    private static final int IDLE_TIMEOUT_MILLIS = 30_000;
    private static final int STOP_TIMEOUT_MILLIS = 2_000;

    private final Server server;
    private final ServerConnector connector;
    private volatile boolean running;

    public AdminServer(AdminDao dao, LogTailer logs, RunManager runs, FailedLinkDao failedLinks,
            SearchService search, int port, String bindAddress) {
        ApiRoutes routes = new ApiRoutes(dao, logs, runs, failedLinks, search,
                new HealthChecker(dao, logs), this::isRunning);
        QueuedThreadPool threadPool = new QueuedThreadPool(WORKER_THREADS);
        threadPool.setName("admin-http");
        threadPool.setDaemon(true);
        this.server = new Server(threadPool);
        this.server.setStopTimeout(STOP_TIMEOUT_MILLIS);
        this.connector = new ServerConnector(server);
        this.connector.setHost(bindAddress);
        this.connector.setPort(port);
        this.connector.setIdleTimeout(IDLE_TIMEOUT_MILLIS);
        this.server.addConnector(connector);
        this.server.setHandler(new AdminHandler(routes, new StaticFiles()));
    }

    public void start() throws IOException {
        try {
            server.start();
        } catch (Exception e) {
            throw new IOException("failed to start the admin server: " + e.getMessage(), e);
        }
        running = true;
    }

    public int port() {
        return connector.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        // 先置 false：两个 SSE 循环每秒检查一次，它们会自己退出。
        running = false;
        try {
            server.stop();
        } catch (Exception e) {
            LOG.warn("failed to stop the admin server: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static final class AdminHandler extends AbstractHandler {
        private final ApiRoutes routes;
        private final StaticFiles staticFiles;

        private AdminHandler(ApiRoutes routes, StaticFiles staticFiles) {
            this.routes = routes;
            this.staticFiles = staticFiles;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                HttpServletResponse response) throws IOException {
            baseRequest.setHandled(true);
            HttpSupport http = new HttpSupport(request, response);
            String path = http.path();
            try {
                if (path.startsWith("/api/")) {
                    routes.handle(http);
                } else {
                    if (!"GET".equalsIgnoreCase(http.method())) {
                        throw new ApiException(405, "only GET is supported");
                    }
                    staticFiles.serve(http);
                }
            } catch (ApiException e) {
                http.sendError(e.getStatus(), e.getMessage());
            } catch (IOException e) {
                // 客户端提前断开（SSE、关页面）属于正常情况
                LOG.debug("connection closed while handling {}", path, e);
            } catch (RuntimeException e) {
                LOG.error("unexpected failure while handling {}", path, e);
                http.sendError(500, "internal server error");
            }
        }
    }
}
