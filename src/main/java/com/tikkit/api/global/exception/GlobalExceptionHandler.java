package com.tikkit.api.global.exception;

import com.tikkit.api.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.FieldError;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(ValidationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ex.getErrorCode().getCode(), ex.getErrorCode().getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        // 예상 못한 예외
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail("SYS_001", "서버 내부 오류가 발생했습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String code = "INVALID_INPUT";
        String message = "입력값이 올바르지 않습니다.";
        if (fieldError != null) {
            message = fieldError.getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(ApiResponse.fail(code, message));
    }
}
