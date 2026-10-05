package com.tikkit.api.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * API 전역에서 쓰는 에러 코드. docs/PRD.md 부록 B의 에러 코드 표와 일치시킨다.
 */
@Getter
public enum ErrorCode {

    // 공통
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 회원 도메인
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 예매 도메인
    // 오픈 전·마감 후를 구분하지 않고 같은 코드를 쓴다 (docs/PRD.md 참조)
    BOOKING_NOT_OPEN(HttpStatus.BAD_REQUEST, "예매 가능한 기간이 아닙니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "잔여 좌석이 없습니다."),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT, "선점 시간이 만료된 예약입니다."),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 요청입니다."),
    DUPLICATE_PENDING_RESERVATION(HttpStatus.CONFLICT, "이미 선점 중인 예약이 있습니다."),
    CANCEL_DEADLINE_PASSED(HttpStatus.CONFLICT, "취소 가능 기한이 지났습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}