package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;

/**
 * 全局异常处理器：把各类异常统一转换为 {@link Result}，避免框架异常泄漏技术细节。
 * <p>
 * 参照 DOVideo-AI 的 ApiExceptionHandler 设计，确保所有错误响应格式一致。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 业务异常：错误码即语义 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException error) {
        ErrorCode code = error.errorCode();
        return build(HttpStatus.resolve(code.code()) != null ? HttpStatus.resolve(code.code()) : HttpStatus.INTERNAL_SERVER_ERROR,
                code.code(), safe(error.getMessage(), "请求处理失败"));
    }

    /** 请求参数缺失 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> missingParam(MissingServletRequestParameterException error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(),
                "缺少必要参数: " + error.getParameterName());
    }

    /** 请求参数类型不匹配 → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> typeMismatch(MethodArgumentTypeMismatchException error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(),
                "参数类型错误: " + error.getName());
    }

    /** 请求体不可读 → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> notReadable(HttpMessageNotReadableException error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(), "请求体格式错误");
    }

    /** 参数不合法 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> badArgument(IllegalArgumentException error) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(),
                safe(error.getMessage(), "请求参数不合法"));
    }

    /** 状态冲突（如重复合并） → 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> conflict(IllegalStateException error) {
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT.code(),
                safe(error.getMessage(), "资源状态冲突"));
    }

    /** 请求方法不允许 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_ARGUMENT.code(), "请求方法不被支持");
    }

    /** 资源不存在 → 404 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<Void>> notFound(NoSuchElementException error) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.code(),
                safe(error.getMessage(), "资源不存在"));
    }

    /** 权限不足 → 403 */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> forbidden(SecurityException error) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.code(),
                safe(error.getMessage(), "无访问权限"));
    }

    /** 兜底：真正未知的异常才落到这里，不对外泄漏技术细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> internalError(Exception error) {
        log.error("未预期的请求处理异常", error);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.code(), "服务暂时不可用");
    }

    private ResponseEntity<Result<Void>> build(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(Result.error(code, message));
    }

    private String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
