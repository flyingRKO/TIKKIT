package com.tikkit.api.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tikkit.api.common.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 모든 API 응답을 감싸는 공통 포맷: {success, data, code, message, errors}.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String code;
    private final String message;
    private final List<String> errors;

    @Builder
    private ApiResponse(boolean success, T data, String code, String message, List<String> errors) {
        this.success = success;
        this.data = data;
        this.code = code;
        this.message = message;
        this.errors = errors;
    }

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    public static ApiResponse<Void> success() {
        return ApiResponse.<Void>builder().success(true).build();
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return error(errorCode, List.of());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, List<String> errors) {
        return ApiResponse.<Void>builder()
                .success(false)
                .code(errorCode.name())
                .message(errorCode.getMessage())
                .errors(errors)
                .build();
    }
}