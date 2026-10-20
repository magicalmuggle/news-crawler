package com.github.magicalmuggle;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jsoup.nodes.Document;

// 一次请求的结果。状态码只有在真正收到 HTTP 响应时才有意义：
// 连接超时、DNS 失败等传输层错误用 statusCode = 0 的 TRANSIENT_ERROR 表示。
public final class FetchResult {
    public enum Outcome {
        OK,
        DEAD,
        RATE_LIMITED,
        TRANSIENT_ERROR
    }

    static final int NO_STATUS = 0;

    private final int statusCode;
    private final Outcome outcome;
    private final Document document;
    private final long retryAfterMillis;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    private FetchResult(int statusCode, Outcome outcome, Document document, long retryAfterMillis) {
        this.statusCode = statusCode;
        this.outcome = outcome;
        this.document = document;
        this.retryAfterMillis = retryAfterMillis;
    }

    static FetchResult ok(int statusCode, Document document) {
        return new FetchResult(statusCode, Outcome.OK, document, 0L);
    }

    static FetchResult dead(int statusCode) {
        return new FetchResult(statusCode, Outcome.DEAD, null, 0L);
    }

    static FetchResult rateLimited(int statusCode, long retryAfterMillis) {
        return new FetchResult(statusCode, Outcome.RATE_LIMITED, null, retryAfterMillis);
    }

    static FetchResult transientError(int statusCode) {
        return new FetchResult(statusCode, Outcome.TRANSIENT_ERROR, null, 0L);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public Document getDocument() {
        return document;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
