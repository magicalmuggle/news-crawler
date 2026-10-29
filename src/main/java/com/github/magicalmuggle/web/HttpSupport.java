package com.github.magicalmuggle.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// Servlet 的收发细节集中在这里，路由代码只关心业务。
public final class HttpSupport {
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final byte[] SSE_CONNECTED = ": connected\n\n".getBytes(StandardCharsets.UTF_8);

    private final HttpServletRequest request;
    private final HttpServletResponse response;

    // 只做一层请求/响应适配，生命周期由容器控制。
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public HttpSupport(HttpServletRequest request, HttpServletResponse response) {
        this.request = request;
        this.response = response;
    }

    public String method() {
        return request.getMethod();
    }

    public String path() {
        return request.getRequestURI();
    }

    public void sendJson(int status, Object body) throws IOException {
        sendBytes(status, "application/json; charset=utf-8", Json.toBytes(body));
    }

    public void sendText(int status, String contentType, String body) throws IOException {
        sendBytes(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public void sendError(int status, String message) {
        // 响应可能已经开始（例如 SSE），此时只能断开连接。
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        try {
            sendJson(status, body);
        } catch (IOException e) {
            // 客户端已提前断开
        }
    }

    // SSE 的响应头在这里一次性写好，两个流式接口共用。
    public OutputStream startEventStream() throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        OutputStream outputStream = response.getOutputStream();
        outputStream.write(SSE_CONNECTED);
        outputStream.flush();
        return outputStream;
    }

    private void sendBytes(int status, String contentType, byte[] payload) throws IOException {
        response.setStatus(status);
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(payload.length);
        OutputStream outputStream = response.getOutputStream();
        outputStream.write(payload);
        outputStream.flush();
    }

    public String readBody() throws IOException {
        byte[] body = readAll(request.getInputStream());
        return new String(body, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = inputStream.read(chunk)) >= 0) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new ApiException(413, "request body is too large");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    public Map<String, String> queryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        String rawQuery = request.getQueryString();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    public static int limitParam(Map<String, String> params) {
        return intParam(params, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
    }

    public static int intParam(Map<String, String> params, String name, int defaultValue, int min, int max) {
        String raw = trimToNull(params.get(name));
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be an integer");
        }
    }

    public static Long longParam(Map<String, String> params, String name) {
        String raw = trimToNull(params.get(name));
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be an integer");
        }
    }

    public static String stringParam(Map<String, String> params, String name) {
        return trimToNull(params.get(name));
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
