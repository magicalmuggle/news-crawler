package com.github.magicalmuggle.web;

// 带 HTTP 状态码的 API 错误，由 AdminServer 统一转成 JSON。
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
