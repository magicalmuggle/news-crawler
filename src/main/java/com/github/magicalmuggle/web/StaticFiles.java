package com.github.magicalmuggle.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 前端是三个手写的静态文件，不需要模板引擎，也不需要暴露任意 classpath 资源。
public final class StaticFiles {
    private static final Set<String> ALLOWED = new HashSet<>(
            Arrays.asList("index.html", "app.js", "style.css"));
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
    }

    public void serve(HttpExchange exchange, String path) throws IOException {
        String name = path.equals("/") || path.isEmpty() ? "index.html" : path.substring(1);
        if (!ALLOWED.contains(name)) {
            HttpSupport.sendError(exchange, 404, "not found");
            return;
        }
        byte[] content = readResource(name);
        if (content == null) {
            HttpSupport.sendError(exchange, 404, "not found");
            return;
        }
        HttpSupport.sendText(exchange, 200, contentTypeOf(name), new String(content, StandardCharsets.UTF_8));
    }

    private String contentTypeOf(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        String contentType = CONTENT_TYPES.get(extension);
        return contentType == null ? "application/octet-stream" : contentType;
    }

    private byte[] readResource(String name) throws IOException {
        InputStream inputStream = StaticFiles.class.getResourceAsStream("/web/" + name);
        if (inputStream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            inputStream.close();
        }
    }
}
