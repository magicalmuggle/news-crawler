package com.github.magicalmuggle;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 测试用的固定响应服务器。它替换了原来基于 com.sun.net.httpserver 的桩，
// 这样测试代码也不会再碰到 com.sun.* 的访问限制。
final class StubHttpServer implements Closeable {
    private final Server server;
    private final int port;

    StubHttpServer(int status, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        this.server = new Server(0);
        this.server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
                baseRequest.setHandled(true);
                response.setStatus(status);
                response.setContentType("text/html; charset=utf-8");
                response.setContentLength(payload.length);
                response.getOutputStream().write(payload);
            }
        });
        this.server.start();
        this.port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop the stub server", e);
        }
    }
}
