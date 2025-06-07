package com.tikkit.api.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // User
    EMAIL_REQUIRED("USER_001", "이메일은 필수 입력값입니다."),
    INVALID_EMAIL_FORMAT("USER_002", "이메일 형식이 올바르지 않습니다."),
    PASSWORD_REQUIRED("USER_003", "비밀번호는 필수 입력값입니다."),
    PASSWORD_LENGTH_INVALID("USER_004", "비밀번호는 10자 이상 20자 이하여야 합니다."),
    NAME_REQUIRED("USER_005", "이름은 필수 입력값입니다."),
    NAME_TOO_LONG("USER_006", "이름은 20자 이하여야 합니다."),
    PHONE_REQUIRED("USER_007", "전화번호는 필수 입력값입니다."),
    INVALID_PHONE_FORMAT("USER_008", "전화번호는 10~11자리 숫자만 입력해야 합니다."),
    DUPLICATE_EMAIL("USER_009", "이미 사용 중인 이메일입니다.");

    private final String code;
    private final String message;
}
