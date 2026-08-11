package com.example.server.common;

/**
 * 统一错误码枚举
 * <p>
 * 业务语义由 {@code code} 承载，HTTP 状态码只表达传输层语义。
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),
    INVALID_ARGUMENT(400, "请求参数不合法"),
    VALIDATION_FAILED(400, "请求参数校验失败"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无访问权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源状态冲突"),
    INTERNAL_ERROR(500, "服务暂时不可用"),
    UNPROCESSABLE(422, "请求无法处理");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() { return code; }
    public String defaultMessage() { return defaultMessage; }
}
