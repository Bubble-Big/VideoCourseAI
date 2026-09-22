package com.example.server.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码枚举。
 * <p>业务语义由 {@code code} 承载，HTTP 状态码由 {@link #httpStatus} 显式表达，
 * 两者解耦，避免 {@code HttpStatus.resolve(code)} 的数值巧合依赖。</p>
 */
public enum ErrorCode {

    SUCCESS(0, "成功", HttpStatus.OK),
    INVALID_ARGUMENT(400, "请求参数不合法", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(400, "请求参数校验失败", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "未认证", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "无访问权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(409, "资源状态冲突", HttpStatus.CONFLICT),
    RATE_LIMITED(429, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    UNPROCESSABLE(422, "请求无法处理", HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(500, "服务暂时不可用", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(503, "服务不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int code() { return code; }
    public String defaultMessage() { return defaultMessage; }
    public HttpStatus httpStatus() { return httpStatus; }
}
