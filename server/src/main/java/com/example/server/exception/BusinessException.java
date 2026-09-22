package com.example.server.exception;

import com.example.server.common.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode} 语义，由全局异常处理器统一转换为 Result 响应。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
