package com.exam.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusiness(BusinessException ex) {
        HttpStatus status = mapStatus(ex.getCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", ex.getCode());
        body.put("error", status.getReasonPhrase());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 将业务错误码映射为真实 HTTP 状态：
     * 401→UNAUTHORIZED、403→FORBIDDEN、404→NOT_FOUND、409→CONFLICT、
     * 422→UNPROCESSABLE_ENTITY；未知/其它错误码默认按 400 BAD_REQUEST 返回
     * （业务层无参构造的 BusinessException 默认 code=422，仍走 422）。
     */
    private HttpStatus mapStatus(int code) {
        switch (code) {
            case 401:
                return HttpStatus.UNAUTHORIZED;
            case 403:
                return HttpStatus.FORBIDDEN;
            case 404:
                return HttpStatus.NOT_FOUND;
            case 409:
                return HttpStatus.CONFLICT;
            case 422:
                return HttpStatus.UNPROCESSABLE_ENTITY;
            default:
                return HttpStatus.BAD_REQUEST;
        }
    }
}
