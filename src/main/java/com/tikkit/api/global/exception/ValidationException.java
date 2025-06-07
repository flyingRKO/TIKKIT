package com.tikkit.api.global.exception;

import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
