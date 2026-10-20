package com.github.magicalmuggle;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

// 全进程共享一个 HttpClient：复用连接（keep-alive），有超时，关掉 HttpClient 自带的静默重试。
// 重试由 Crawler 统一负责，否则"1 req/s"在失败时会被悄悄翻倍。
public class HttpFetcher implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpFetcher.class);

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    // 不要加 br：HttpClient 默认只解 gzip / deflate，拿到 br 会解析成空文档。
    private static final String ACCEPT_ENCODING = "gzip, deflate";
    private static final int MAX_BODY_SNIPPET_LENGTH = 200;
    private static final long MAX_RETRY_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final CloseableHttpClient httpClient;

    public HttpFetcher(int maxConnections) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .setMaxRedirects(5)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ofSeconds(1)))
                .build();
    }

    public FetchResult fetch(String url) throws IOException {
        HttpGet request = new HttpGet(cleanUrl(url));
        request.setHeader("user-agent", USER_AGENT);
        request.setHeader("accept", ACCEPT);
        request.setHeader("accept-language", ACCEPT_LANGUAGE);
        request.setHeader("accept-encoding", ACCEPT_ENCODING);

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getCode();
            byte[] body = readBody(response.getEntity());
            if (statusCode == HttpStatus.SC_OK) {
                Document document = Jsoup.parse(new ByteArrayInputStream(body), null, url);
                return FetchResult.ok(statusCode, document);
            }
            long retryAfterMillis = readRetryAfterMillis(response);
            if (LOG.isDebugEnabled()) {
                LOG.debug("response {} for {}: {}", statusCode, url, snippet(body));
            }
            if (isBlocked(statusCode)) {
                return FetchResult.rateLimited(statusCode, retryAfterMillis);
            }
            if (statusCode >= 400 && statusCode < 500) {
                return FetchResult.dead(statusCode);
            }
            return FetchResult.transientError(statusCode);
        }
    }

    private static boolean isBlocked(int statusCode) {
        return statusCode == HttpStatus.SC_FORBIDDEN
                || statusCode == HttpStatus.SC_TOO_MANY_REQUESTS
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE;
    }

    private static byte[] readBody(HttpEntity entity) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        return EntityUtils.toByteArray(entity);
    }

    private static long readRetryAfterMillis(CloseableHttpResponse response) {
        Header header = response.getFirstHeader("Retry-After");
        if (header == null) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(header.getValue().trim());
            if (seconds < 0L) {
                return 0L;
            }
            return Math.min(TimeUnit.SECONDS.toMillis(seconds), MAX_RETRY_AFTER_MILLIS);
        } catch (NumberFormatException e) {
            // Retry-After 也可能是 HTTP 日期，这里不解析，退回指数退避。
            return 0L;
        }
    }

    private static String snippet(byte[] body) {
        int length = Math.min(body.length, MAX_BODY_SNIPPET_LENGTH);
        return new String(body, 0, length, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }

    private String cleanUrl(String url) {
        return url.replace("{", "").replace("}", "");
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
